package com.ganzi.emori.orb;

import com.ganzi.emori.api.ApiException;
import com.ganzi.emori.api.DbSupport;
import com.ganzi.emori.api.Idempotency;
import com.ganzi.emori.friend.FriendModels.UserSummary;
import com.ganzi.emori.friend.FriendService;
import com.ganzi.emori.orb.DispatchModels.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class DispatchService {
    private final JdbcTemplate jdbc;
    private final DbSupport db;
    private final Idempotency idempotency;
    private final FriendService friends;
    private final ObjectMapper json;

    public DispatchService(JdbcTemplate jdbc, DbSupport db, Idempotency idempotency,
                           FriendService friends, ObjectMapper json) {
        this.jdbc = jdbc; this.db = db; this.idempotency = idempotency; this.friends = friends; this.json = json;
    }

    @Transactional
    public String create(UUID actor, UUID orbId, UUID key, DispatchRequest input) {
        if (input == null || input.route() == null) throw ApiException.invalid("route가 필요합니다.");
        UUID target = null;
        if ("FRIEND".equals(input.route())) {
            if (input.friendId() == null) throw ApiException.invalid("friendId가 필요합니다.");
            target = DbSupport.requiredId(input.friendId());
            if (actor.equals(target)) throw ApiException.invalid("자기 자신에게 친구 경로로 보낼 수 없습니다.");
        } else if ("SELF".equals(input.route())) {
            if (input.friendId() != null) throw ApiException.invalid("SELF에는 friendId를 사용할 수 없습니다.");
        } else if ("ANONYMOUS".equals(input.route())) {
            if (input.friendId() != null) throw ApiException.invalid("ANONYMOUS에는 friendId를 사용할 수 없습니다.");
            throw new ApiException(HttpStatus.FORBIDDEN, "AGE_REQUIRED", "연령 확인 전에는 익명 전송을 사용할 수 없습니다.");
        } else {
            throw ApiException.invalid("route를 확인해주세요.");
        }
        if (target == null) db.lockUsers(actor);
        else db.lockUsers(actor, target);
        String path = "/api/v1/orbs/" + orbId + "/dispatches";
        UUID recipient = target;
        return idempotency.execute(actor, "POST", path, key, json.writeValueAsString(input),
                () -> createNew(actor, orbId, input.route(), recipient));
    }

    private DispatchResponse createNew(UUID actor, UUID orbId, String route, UUID recipient) {
        List<UUID> authors = jdbc.query("SELECT author_id FROM orb WHERE id=? FOR UPDATE",
                (rs, n) -> rs.getObject(1, UUID.class), orbId);
        if (authors.isEmpty() || !actor.equals(authors.getFirst())) throw missing();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM dispatch WHERE orb_id=?", Integer.class, orbId) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_DISPATCHED", "이미 확정한 구슬입니다.");
        }
        if (recipient != null && !friends.friends(actor, recipient)) {
            throw new ApiException(HttpStatus.CONFLICT, "FRIENDSHIP_CHANGED", "현재 친구 관계를 확인해주세요.");
        }
        UUID dispatchId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        OffsetDateTime now = DbSupport.now();
        OffsetDateTime cancelUntil = recipient == null ? null : now.plusMinutes(5);
        jdbc.update("INSERT INTO dispatch(id,orb_id,route,sent_at,cancel_until,recipient_count) VALUES (?,?,?,?,?,?)",
                dispatchId, orbId, route, now, cancelUntil, recipient == null ? 0 : 1);
        jdbc.update("""
                INSERT INTO emotion_record(id,owner_id,dispatch_id,own_dispatch_id,receipt_id,side,recorded_at)
                VALUES (?,?,?,?,NULL,'OWN',?)
                """, recordId, actor, dispatchId, dispatchId, now);
        if (recipient != null) {
            UUID receiptId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO receipt(id,target_id,dispatch_id,recipient_id,received_at)
                    VALUES (?,?,?,?,?)
                    """, receiptId, UUID.randomUUID(), dispatchId, recipient, now);
            jdbc.update("""
                    INSERT INTO emotion_record(id,owner_id,dispatch_id,own_dispatch_id,receipt_id,side,recorded_at)
                    VALUES (?,?,?,NULL,?,'RECEIVED',?)
                    """, UUID.randomUUID(), recipient, dispatchId, receiptId, now);
        }
        return new DispatchResponse(get(actor, dispatchId.toString()), recordId.toString());
    }

    public Dispatch get(UUID actor, String dispatchText) {
        UUID id = DbSupport.pathId(dispatchText);
        List<DispatchRow> rows = jdbc.query("""
                SELECT d.id,d.orb_id,d.route,d.sent_at,d.cancel_until,d.recipient_count,o.author_id
                FROM dispatch d JOIN orb o ON o.id=d.orb_id WHERE d.id=?
                """, (rs, n) -> new DispatchRow(rs.getObject("id", UUID.class), rs.getObject("orb_id", UUID.class),
                rs.getString("route"), rs.getObject("sent_at", OffsetDateTime.class),
                rs.getObject("cancel_until", OffsetDateTime.class), rs.getInt("recipient_count"),
                rs.getObject("author_id", UUID.class)), id);
        if (rows.isEmpty() || !actor.equals(rows.getFirst().author())) throw missing();
        DispatchRow row = rows.getFirst();
        List<Target> targets = jdbc.query("""
                SELECT r.target_id,r.opened_at,r.cancelled_at,u.id AS recipient_id,u.nickname
                FROM receipt r JOIN app_user u ON u.id=r.recipient_id
                WHERE r.dispatch_id=? ORDER BY r.received_at,r.id
                """, (rs, n) -> {
            OffsetDateTime opened = rs.getObject("opened_at", OffsetDateTime.class);
            OffsetDateTime cancelled = rs.getObject("cancelled_at", OffsetDateTime.class);
            UserSummary counterpart = "FRIEND".equals(row.route())
                    ? new UserSummary(rs.getObject("recipient_id", UUID.class).toString(), rs.getString("nickname")) : null;
            boolean canCancel = cancelled == null && opened == null && row.cancelUntil() != null
                    && !DbSupport.now().isAfter(row.cancelUntil());
            return new Target(rs.getObject("target_id", UUID.class).toString(), counterpart,
                    cancelled == null ? "DELIVERED" : "CANCELLED",
                    opened == null ? null : opened.toInstant().toString(),
                    cancelled == null ? null : cancelled.toInstant().toString(), canCancel);
        }, id);
        long cancelled = targets.stream().filter(t -> "CANCELLED".equals(t.status())).count();
        String status = "SELF".equals(row.route()) ? "SAVED" : cancelled == 0 ? "SENT"
                : cancelled == row.recipientCount() ? "CANCELLED" : "PARTIALLY_CANCELLED";
        return new Dispatch(id.toString(), row.orbId().toString(), row.route(), status, row.recipientCount(),
                row.sentAt().toInstant().toString(), row.cancelUntil() == null ? null : row.cancelUntil().toInstant().toString(), targets);
    }

    private ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "대상을 찾을 수 없습니다."); }
    private record DispatchRow(UUID id, UUID orbId, String route, OffsetDateTime sentAt,
                               OffsetDateTime cancelUntil, int recipientCount, UUID author) {}
}
