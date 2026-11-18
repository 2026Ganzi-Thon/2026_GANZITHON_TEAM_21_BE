package com.ganzi.emori.friend;

import com.ganzi.emori.api.ApiException;
import com.ganzi.emori.api.DbSupport;
import com.ganzi.emori.api.DbSupport.Cursor;
import com.ganzi.emori.api.DbSupport.Page;
import com.ganzi.emori.friend.FriendModels.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class FriendService {
    private static final Pattern FRIEND_CODE = Pattern.compile("[a-z0-9_]{4,20}");
    private final JdbcTemplate jdbc;
    private final DbSupport db;

    public FriendService(JdbcTemplate jdbc, DbSupport db) { this.jdbc = jdbc; this.db = db; }

    @Transactional
    public FriendRequestView request(UUID actor, FriendCodeRequest input) {
        if (input == null || input.friendCode() == null) throw ApiException.invalid("friendCode가 필요합니다.");
        String code = input.friendCode().toLowerCase(Locale.ROOT);
        if (!FRIEND_CODE.matcher(code).matches()) throw ApiException.invalid("친구 코드를 확인해주세요.");
        List<UUID> matches = jdbc.query("SELECT id FROM app_user WHERE login_id=?",
                (rs, n) -> rs.getObject(1, UUID.class), code);
        if (matches.isEmpty()) throw missing();
        UUID target = matches.getFirst();
        if (actor.equals(target)) throw ApiException.invalid("자기 자신에게 친구 요청을 보낼 수 없습니다.");
        db.lockUsers(actor, target);
        if (friends(actor, target) || !jdbc.query("SELECT id FROM friend_request WHERE pending_pair_key=?",
                (rs, n) -> rs.getObject(1, UUID.class), DbSupport.pair(actor, target)).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "RELATION_EXISTS", "이미 친구이거나 진행 중인 요청이 있습니다.");
        }
        UUID id = UUID.randomUUID();
        OffsetDateTime now = DbSupport.now();
        jdbc.update("""
                INSERT INTO friend_request(id,from_user_id,to_user_id,status,pending_pair_key,created_at)
                VALUES (?,?,?,'PENDING',?,?)
                """, id, actor, target, DbSupport.pair(actor, target), now);
        return getRequest(id);
    }

    @Transactional
    public FriendRequestView decide(UUID actor, String idText, DecisionRequest input) {
        UUID id = DbSupport.pathId(idText);
        if (input == null || !("ACCEPT".equals(input.decision()) || "DECLINE".equals(input.decision()))) {
            throw ApiException.invalid("decision은 ACCEPT 또는 DECLINE이어야 합니다.");
        }
        List<RequestIds> ids = jdbc.query("SELECT from_user_id,to_user_id FROM friend_request WHERE id=?",
                (rs, n) -> new RequestIds(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), id);
        if (ids.isEmpty() || !actor.equals(ids.getFirst().to())) throw missing();
        db.lockUsers(ids.getFirst().from(), ids.getFirst().to());
        List<DecisionState> states = jdbc.query("SELECT from_user_id,to_user_id,status FROM friend_request WHERE id=? FOR UPDATE",
                (rs, n) -> new DecisionState(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)), id);
        if (states.isEmpty() || !actor.equals(states.getFirst().to())) throw missing();
        DecisionState state = states.getFirst();
        String desired = "ACCEPT".equals(input.decision()) ? "ACCEPTED" : "DECLINED";
        if (!"PENDING".equals(state.status())) {
            if (desired.equals(state.status())) return getRequest(id);
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_CLOSED", "이미 다른 결정으로 종료된 요청입니다.");
        }
        OffsetDateTime now = DbSupport.now();
        if ("ACCEPTED".equals(desired)) {
            jdbc.update("INSERT INTO friendship(user_low_id,user_high_id,created_at) VALUES (?,?,?)",
                    DbSupport.low(state.from(), state.to()), DbSupport.high(state.from(), state.to()), now);
        }
        jdbc.update("UPDATE friend_request SET status=?,pending_pair_key=NULL,decided_at=? WHERE id=?",
                desired, now, id);
        return getRequest(id);
    }

    @Transactional
    public void delete(UUID actor, String targetText) {
        UUID target = DbSupport.pathId(targetText);
        if (actor.equals(target)) return;
        if (jdbc.query("SELECT id FROM app_user WHERE id=?", (rs, n) -> rs.getObject(1, UUID.class), target).isEmpty()) return;
        db.lockUsers(actor, target);
        jdbc.update("DELETE FROM friendship WHERE user_low_id=? AND user_high_id=?",
                DbSupport.low(actor, target), DbSupport.high(actor, target));
    }

    public Page<UserSummary> friends(UUID actor, String token, Integer requestedLimit) {
        int limit = DbSupport.limit(requestedLimit);
        Cursor cursor = DbSupport.cursor(token, "friends");
        String sql = """
                SELECT u.id AS counterpart_id,u.nickname,f.created_at FROM friendship f
                JOIN app_user u ON u.id=CASE WHEN f.user_low_id=? THEN f.user_high_id ELSE f.user_low_id END
                WHERE (f.user_low_id=? OR f.user_high_id=?)
                """ + (cursor == null ? "" : " AND (f.created_at<? OR (f.created_at=? AND u.id<?)) ")
                + " ORDER BY f.created_at DESC,u.id DESC LIMIT ?";
        List<Object> args = new ArrayList<>(List.of(actor, actor, actor));
        if (cursor != null) { args.add(cursor.at()); args.add(cursor.at()); args.add(cursor.id()); }
        args.add(limit + 1);
        List<FriendRow> rows = jdbc.query(sql, (rs, n) -> new FriendRow(
                rs.getObject("counterpart_id", UUID.class), rs.getString("nickname"),
                rs.getObject("created_at", OffsetDateTime.class)), args.toArray());
        boolean more = rows.size() > limit;
        List<FriendRow> page = rows.subList(0, Math.min(limit, rows.size()));
        String next = more ? DbSupport.cursor("friends", page.getLast().createdAt(), page.getLast().id()) : null;
        return new Page<>(page.stream().map(r -> new UserSummary(r.id().toString(), r.nickname())).toList(), next);
    }

    public Page<FriendRequestView> requests(UUID actor, String direction, String token, Integer requestedLimit) {
        if (!"INCOMING".equals(direction) && !"OUTGOING".equals(direction)) {
            throw ApiException.invalid("direction은 INCOMING 또는 OUTGOING이어야 합니다.");
        }
        int limit = DbSupport.limit(requestedLimit);
        String scope = "requests:" + direction;
        Cursor cursor = DbSupport.cursor(token, scope);
        String column = "INCOMING".equals(direction) ? "r.to_user_id" : "r.from_user_id";
        String sql = """
                SELECT r.id,r.status,r.created_at,fu.id AS from_id,fu.nickname AS from_nickname,
                       tu.id AS to_id,tu.nickname AS to_nickname FROM friend_request r
                JOIN app_user fu ON fu.id=r.from_user_id JOIN app_user tu ON tu.id=r.to_user_id
                """ + " WHERE " + column + "=?" + (cursor == null ? "" : " AND (r.created_at<? OR (r.created_at=? AND r.id<?))")
                + " ORDER BY r.created_at DESC,r.id DESC LIMIT ?";
        List<Object> args = new ArrayList<>(List.of(actor));
        if (cursor != null) { args.add(cursor.at()); args.add(cursor.at()); args.add(cursor.id()); }
        args.add(limit + 1);
        List<RequestRow> rows = jdbc.query(sql, (rs, n) -> mapRequest(rs), args.toArray());
        boolean more = rows.size() > limit;
        List<RequestRow> page = rows.subList(0, Math.min(limit, rows.size()));
        String next = more ? DbSupport.cursor(scope, page.getLast().createdAt(), page.getLast().id()) : null;
        return new Page<>(page.stream().map(RequestRow::view).toList(), next);
    }

    public boolean friends(UUID a, UUID b) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM friendship WHERE user_low_id=? AND user_high_id=?",
                Integer.class, DbSupport.low(a, b), DbSupport.high(a, b)) > 0;
    }

    private FriendRequestView getRequest(UUID id) {
        List<RequestRow> rows = jdbc.query("""
                SELECT r.id,r.status,r.created_at,fu.id AS from_id,fu.nickname AS from_nickname,
                       tu.id AS to_id,tu.nickname AS to_nickname FROM friend_request r
                JOIN app_user fu ON fu.id=r.from_user_id JOIN app_user tu ON tu.id=r.to_user_id WHERE r.id=?
                """, (rs, n) -> mapRequest(rs), id);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst().view();
    }

    private RequestRow mapRequest(ResultSet rs) throws SQLException {
        return new RequestRow(rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class),
                new UserSummary(rs.getObject("from_id", UUID.class).toString(), rs.getString("from_nickname")),
                new UserSummary(rs.getObject("to_id", UUID.class).toString(), rs.getString("to_nickname")));
    }

    private ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "대상을 찾을 수 없습니다."); }
    private record RequestIds(UUID from, UUID to) {}
    private record DecisionState(UUID from, UUID to, String status) {}
    private record FriendRow(UUID id, String nickname, OffsetDateTime createdAt) {}
    private record RequestRow(UUID id, String status, OffsetDateTime createdAt, UserSummary from, UserSummary to) {
        FriendRequestView view() { return new FriendRequestView(id.toString(), from, to, status, createdAt.toInstant().toString()); }
    }
}
