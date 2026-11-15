package com.ganzi.emori.record;

import com.ganzi.emori.api.ApiException;
import com.ganzi.emori.api.DbSupport;
import com.ganzi.emori.api.DbSupport.Cursor;
import com.ganzi.emori.api.DbSupport.Page;
import com.ganzi.emori.friend.FriendModels.UserSummary;
import com.ganzi.emori.orb.DispatchService;
import com.ganzi.emori.orb.OrbService;
import com.ganzi.emori.record.RecordModels.*;
import com.ganzi.emori.record.RecordModels.Record;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class RecordService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final DbSupport db;
    private final OrbService orbs;
    private final DispatchService dispatches;
    public RecordService(JdbcTemplate jdbc, DbSupport db, OrbService orbs, DispatchService dispatches) {
        this.jdbc = jdbc; this.db = db; this.orbs = orbs; this.dispatches = dispatches;
    }

    public Page<Record> list(UUID actor, String side, String from, String to, String date,
                             String token, Integer requestedLimit) {
        if (side != null && !side.equals("OWN") && !side.equals("RECEIVED")) throw ApiException.invalid("Invalid side");
        if (date != null && (from != null || to != null)) throw ApiException.invalid("date cannot be combined with from or to");
        LocalDate first = parseDate(date == null ? from : date);
        LocalDate last = parseDate(date == null ? to : date);
        if (first != null && last != null && first.isAfter(last)) throw ApiException.invalid("from must precede to");
        int limit = DbSupport.limit(requestedLimit);
        String scope = "records:" + side + ":" + first + ":" + last;
        Cursor cursor = DbSupport.cursor(token, scope);
        StringBuilder sql = new StringBuilder("""
                SELECT er.id,er.side,er.recorded_at,er.receipt_id,er.dispatch_id,d.route,d.orb_id
                FROM emotion_record er JOIN dispatch d ON d.id=er.dispatch_id
                LEFT JOIN receipt r ON r.id=er.receipt_id
                WHERE er.owner_id=? AND er.deleted_at IS NULL
                AND (er.side='OWN' OR r.cancelled_at IS NULL)
                """);
        List<Object> args = new ArrayList<>(); args.add(actor);
        if (side != null) { sql.append(" AND er.side=?"); args.add(side); }
        if (first != null) { sql.append(" AND er.recorded_at>=?"); args.add(start(first)); }
        if (last != null) { sql.append(" AND er.recorded_at<?"); args.add(start(last.plusDays(1))); }
        if (cursor != null) {
            sql.append(" AND (er.recorded_at<? OR (er.recorded_at=? AND er.id<?))");
            args.add(cursor.at()); args.add(cursor.at()); args.add(cursor.id());
        }
        sql.append(" ORDER BY er.recorded_at DESC,er.id DESC LIMIT ?"); args.add(limit + 1);
        List<Row> rows = jdbc.query(sql.toString(), (rs, n) -> new Row(
                rs.getObject("id", UUID.class), rs.getString("side"),
                rs.getObject("recorded_at", OffsetDateTime.class), rs.getObject("receipt_id", UUID.class),
                rs.getObject("dispatch_id", UUID.class), rs.getString("route"),
                rs.getObject("orb_id", UUID.class)), args.toArray());
        boolean more = rows.size() > limit;
        List<Row> page = rows.subList(0, Math.min(limit, rows.size()));
        String next = more ? DbSupport.cursor(scope, page.getLast().at(), page.getLast().id()) : null;
        return new Page<>(page.stream().map(this::view).toList(), next);
    }

    public RecordDetail detail(UUID actor, String idText) {
        Row row = owned(actor, DbSupport.pathId(idText));
        return new RecordDetail(view(row), row.side().equals("OWN")
                ? dispatches.get(actor, row.dispatchId().toString()) : null, List.of());
    }

    @Transactional
    public void delete(UUID actor, String idText) {
        UUID id = DbSupport.pathId(idText);
        db.lockUsers(actor);
        List<String> sides = jdbc.query("SELECT side FROM emotion_record WHERE id=? AND owner_id=? FOR UPDATE",
                (rs, n) -> rs.getString(1), id, actor);
        if (sides.isEmpty()) throw missing();
        if (sides.getFirst().equals("RECEIVED"))
            throw new ApiException(HttpStatus.FORBIDDEN, "ACTION_NOT_ALLOWED", "Received records cannot be deleted");
        jdbc.update("UPDATE emotion_record SET deleted_at=? WHERE id=? AND deleted_at IS NULL", DbSupport.now(), id);
    }

    private Row owned(UUID actor, UUID id) {
        List<Row> rows = jdbc.query("""
                SELECT er.id,er.side,er.recorded_at,er.receipt_id,er.dispatch_id,d.route,d.orb_id
                FROM emotion_record er JOIN dispatch d ON d.id=er.dispatch_id
                LEFT JOIN receipt r ON r.id=er.receipt_id
                WHERE er.id=? AND er.owner_id=? AND er.deleted_at IS NULL
                AND (er.side='OWN' OR r.cancelled_at IS NULL)
                """, (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("side"),
                rs.getObject("recorded_at", OffsetDateTime.class), rs.getObject("receipt_id", UUID.class),
                rs.getObject("dispatch_id", UUID.class), rs.getString("route"),
                rs.getObject("orb_id", UUID.class)), id, actor);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    private Record view(Row row) {
        UserSummary counterpart = null;
        if (row.route().equals("FRIEND")) {
            List<UserSummary> users = row.side().equals("OWN")
                    ? jdbc.query("""
                        SELECT u.id,u.nickname FROM receipt r JOIN app_user u ON u.id=r.recipient_id
                        WHERE r.dispatch_id=?
                        """, (rs, n) -> new UserSummary(rs.getObject(1, UUID.class).toString(), rs.getString(2)), row.dispatchId())
                    : jdbc.query("""
                        SELECT u.id,u.nickname FROM orb o JOIN app_user u ON u.id=o.author_id WHERE o.id=?
                        """, (rs, n) -> new UserSummary(rs.getObject(1, UUID.class).toString(), rs.getString(2)), row.orbId());
            if (!users.isEmpty()) counterpart = users.getFirst();
        }
        return new Record(row.id().toString(), row.side(), orbs.snapshot(row.orbId()), row.route(),
                row.at().toInstant().toString(), row.receiptId() == null ? null : row.receiptId().toString(),
                row.dispatchId().toString(), counterpart);
    }

    private static LocalDate parseDate(String value) {
        try { return value == null ? null : LocalDate.parse(value); }
        catch (RuntimeException e) { throw ApiException.invalid("Date must be YYYY-MM-DD"); }
    }
    private static OffsetDateTime start(LocalDate date) {
        return date.atStartOfDay(KST).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Record not found"); }
    private record Row(UUID id, String side, OffsetDateTime at, UUID receiptId, UUID dispatchId,
                       String route, UUID orbId) {}
}
