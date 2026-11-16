package com.ganzi.emori.delivery;

import com.ganzi.emori.api.ApiException;
import com.ganzi.emori.api.DbSupport;
import com.ganzi.emori.api.DbSupport.Cursor;
import com.ganzi.emori.api.DbSupport.Page;
import com.ganzi.emori.api.Idempotency;
import com.ganzi.emori.delivery.DeliveryModels.*;
import com.ganzi.emori.friend.FriendModels.UserSummary;
import com.ganzi.emori.orb.DispatchService;
import com.ganzi.emori.orb.OrbService;
import com.ganzi.emori.push.PushOutboxService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DeliveryService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final DbSupport db;
    private final Idempotency idempotency;
    private final OrbService orbs;
    private final DispatchService dispatches;
    private final PushOutboxService push;

    public DeliveryService(JdbcTemplate jdbc, DbSupport db, Idempotency idempotency,
                           OrbService orbs, DispatchService dispatches,PushOutboxService push) {
        this.jdbc = jdbc; this.db = db; this.idempotency = idempotency;
        this.orbs = orbs; this.dispatches = dispatches; this.push=push;
    }

    public Page<Receipt> inbox(UUID actor, boolean unreadOnly, String dateText, String token, Integer requestedLimit) {
        int limit = DbSupport.limit(requestedLimit);
        LocalDate date = parseDate(dateText);
        String scope = "inbox:" + unreadOnly + ":" + (date == null ? "" : date);
        Cursor cursor = DbSupport.cursor(token, scope);
        StringBuilder sql = new StringBuilder("""
                SELECT r.id,r.dispatch_id,r.received_at,r.opened_at,d.route,d.orb_id,
                       o.author_id,u.nickname AS sender_nickname
                FROM receipt r JOIN dispatch d ON d.id=r.dispatch_id
                JOIN orb o ON o.id=d.orb_id JOIN app_user u ON u.id=o.author_id
                WHERE r.recipient_id=? AND r.cancelled_at IS NULL
                """);
        List<Object> args = new ArrayList<>(); args.add(actor);
        if (unreadOnly) sql.append(" AND r.opened_at IS NULL");
        if (date != null) {
            sql.append(" AND r.received_at>=? AND r.received_at<?");
            args.add(start(date)); args.add(start(date.plusDays(1)));
        }
        if (cursor != null) {
            sql.append(" AND (r.received_at<? OR (r.received_at=? AND r.id<?))");
            args.add(cursor.at()); args.add(cursor.at()); args.add(cursor.id());
        }
        sql.append(" ORDER BY r.received_at DESC,r.id DESC LIMIT ?"); args.add(limit + 1);
        List<Row> rows = jdbc.query(sql.toString(), (rs, n) -> new Row(
                rs.getObject("id", UUID.class), rs.getObject("dispatch_id", UUID.class),
                rs.getObject("received_at", OffsetDateTime.class), rs.getObject("opened_at", OffsetDateTime.class),
                rs.getString("route"), rs.getObject("orb_id", UUID.class),
                rs.getObject("author_id", UUID.class), rs.getString("sender_nickname")), args.toArray());
        boolean more = rows.size() > limit;
        List<Row> page = rows.subList(0, Math.min(limit, rows.size()));
        String next = more ? DbSupport.cursor(scope, page.getLast().receivedAt(), page.getLast().id()) : null;
        return new Page<>(page.stream().map(this::view).toList(), next);
    }

    @Transactional
    public String open(UUID actor, UUID receiptId, UUID key) {
        Row initial = owned(actor, receiptId);
        lockPath(actor, initial.senderId(), initial.orbId(), initial.dispatchId(), receiptId);
        String path = "/api/v1/receipts/" + receiptId + "/open";
        return idempotency.execute(actor, "POST", path, key, "{}", 200, () -> {
            Row row = owned(actor, receiptId);
            if (row.openedAt() == null) {
                OffsetDateTime now=DbSupport.now();
                String token=null;
                if("ANONYMOUS".equals(row.route())) {
                    byte[] bytes=new byte[32]; RANDOM.nextBytes(bytes);
                    token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                    jdbc.update("""
                            UPDATE receipt SET opened_at=?,action_token_hash=?,action_expires_at=?
                            WHERE id=? AND opened_at IS NULL AND cancelled_at IS NULL
                            """,now,DbSupport.hash(token),now.plusMinutes(10),receiptId);
                } else {
                    jdbc.update("UPDATE receipt SET opened_at=? WHERE id=? AND opened_at IS NULL AND cancelled_at IS NULL",
                            now,receiptId);
                }
                return new SingleView(view(owned(actor, receiptId)), orbs.snapshot(row.orbId()),
                        "FIRST",token==null?List.of():List.of("REACT","QUIET"),token);
            }
            if("ANONYMOUS".equals(row.route())) closeAction(receiptId);
            return new SingleView(view(row), orbs.snapshot(row.orbId()), "REPLAY", List.of(), null);
        });
    }

    @Transactional
    public SingleView replay(UUID actor, String idText) {
        UUID id=DbSupport.pathId(idText);
        Row row=owned(actor,id);
        if (row.openedAt() == null) throw new ApiException(HttpStatus.CONFLICT, "NOT_OPENED", "아직 열람하지 않은 구슬입니다.");
        if("ANONYMOUS".equals(row.route())) {
            jdbc.query("SELECT id FROM receipt WHERE id=? FOR UPDATE",(rs,n)->rs.getObject(1),id);
            closeAction(id);
        }
        return new SingleView(view(row), orbs.snapshot(row.orbId()), "REPLAY", List.of(), null);
    }

    private void closeAction(UUID id) {
        jdbc.update("""
                UPDATE receipt SET action_closed_at=? WHERE id=? AND action_token_hash IS NOT NULL
                AND action_closed_at IS NULL
                """,DbSupport.now(),id);
    }

    @Transactional
    public String cancel(UUID actor, UUID dispatchId, UUID targetId, UUID key) {
        List<CancelIds> ids = jdbc.query("""
                SELECT o.author_id,o.id AS orb_id,r.recipient_id,r.id AS receipt_id
                FROM dispatch d JOIN orb o ON o.id=d.orb_id
                JOIN receipt r ON r.dispatch_id=d.id
                WHERE d.id=? AND r.target_id=?
                """, (rs, n) -> new CancelIds(rs.getObject("author_id", UUID.class),
                rs.getObject("orb_id", UUID.class), rs.getObject("recipient_id", UUID.class),
                rs.getObject("receipt_id", UUID.class)), dispatchId, targetId);
        if (ids.isEmpty() || !ids.getFirst().sender().equals(actor)) throw missing();
        CancelIds selected = ids.getFirst();
        lockPath(actor, selected.recipient(), selected.orbId(), dispatchId, selected.receiptId());
        String path = "/api/v1/dispatches/" + dispatchId + "/targets/" + targetId + "/cancel";
        return idempotency.execute(actor, "POST", path, key, "{}", 200, () -> {
            List<CancelState> states = jdbc.query("""
                    SELECT r.opened_at,r.cancelled_at,d.cancel_until FROM receipt r
                    JOIN dispatch d ON d.id=r.dispatch_id WHERE r.id=?
                    """, (rs, n) -> new CancelState(rs.getObject(1, OffsetDateTime.class),
                    rs.getObject(2, OffsetDateTime.class), rs.getObject(3, OffsetDateTime.class)), selected.receiptId());
            if (states.isEmpty()) throw missing();
            CancelState state = states.getFirst();
            OffsetDateTime now = DbSupport.now(); // Read only after all locks are acquired.
            if (state.openedAt() != null || state.cancelledAt() != null || state.cancelUntil() == null
                    || now.isAfter(state.cancelUntil())) {
                throw new ApiException(HttpStatus.CONFLICT, "CANCEL_NOT_ALLOWED", "취소할 수 없는 수신 대상입니다.");
            }
            int changed = jdbc.update("""
                    UPDATE receipt SET cancelled_at=? WHERE id=? AND opened_at IS NULL AND cancelled_at IS NULL
                    """, now, selected.receiptId());
            if (changed != 1) throw new ApiException(HttpStatus.CONFLICT, "CANCEL_NOT_ALLOWED", "취소할 수 없는 수신 대상입니다.");
            push.cancel(selected.receiptId());
            return Map.of("dispatch", dispatches.get(actor, dispatchId.toString()));
        });
    }

    private void lockPath(UUID actor, UUID other, UUID orbId, UUID dispatchId, UUID receiptId) {
        db.lockUsers(actor, other);
        jdbc.query("SELECT id FROM orb WHERE id=? FOR UPDATE", (rs, n) -> rs.getObject(1), orbId);
        jdbc.query("SELECT id FROM dispatch WHERE id=? FOR UPDATE", (rs, n) -> rs.getObject(1), dispatchId);
        jdbc.query("SELECT id FROM receipt WHERE id=? FOR UPDATE", (rs, n) -> rs.getObject(1), receiptId);
    }

    private Row owned(UUID actor, UUID receiptId) {
        List<Row> rows = jdbc.query("""
                SELECT r.id,r.dispatch_id,r.received_at,r.opened_at,d.route,d.orb_id,
                       o.author_id,u.nickname AS sender_nickname
                FROM receipt r JOIN dispatch d ON d.id=r.dispatch_id
                JOIN orb o ON o.id=d.orb_id JOIN app_user u ON u.id=o.author_id
                WHERE r.id=? AND r.recipient_id=? AND r.cancelled_at IS NULL
                """, (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("dispatch_id", UUID.class),
                rs.getObject("received_at", OffsetDateTime.class), rs.getObject("opened_at", OffsetDateTime.class),
                rs.getString("route"), rs.getObject("orb_id", UUID.class),
                rs.getObject("author_id", UUID.class), rs.getString("sender_nickname")), receiptId, actor);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    private Receipt view(Row row) {
        UserSummary sender = "FRIEND".equals(row.route())
                ? new UserSummary(row.senderId().toString(), row.senderNickname()) : null;
        return new Receipt(row.id().toString(), row.route(), sender, row.receivedAt().toInstant().toString(),
                row.openedAt() == null ? null : row.openedAt().toInstant().toString(), orbs.snapshot(row.orbId()));
    }

    private static LocalDate parseDate(String value) {
        try { return value == null ? null : LocalDate.parse(value); }
        catch (RuntimeException e) { throw ApiException.invalid("date는 YYYY-MM-DD 형식이어야 합니다."); }
    }
    private static OffsetDateTime start(LocalDate date) { return date.atStartOfDay(KST).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC); }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "대상을 찾을 수 없습니다."); }
    private record Row(UUID id, UUID dispatchId, OffsetDateTime receivedAt, OffsetDateTime openedAt,
                       String route, UUID orbId, UUID senderId, String senderNickname) {}
    private record CancelIds(UUID sender, UUID orbId, UUID recipient, UUID receiptId) {}
    private record CancelState(OffsetDateTime openedAt, OffsetDateTime cancelledAt, OffsetDateTime cancelUntil) {}
}
