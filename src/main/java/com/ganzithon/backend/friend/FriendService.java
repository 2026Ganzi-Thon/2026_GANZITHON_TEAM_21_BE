package com.ganzithon.backend.friend;

import com.ganzithon.backend.api.ApiException;
import com.ganzithon.backend.api.ApiModels.FriendRequest;
import com.ganzithon.backend.common.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class FriendService {

    private final Db db;

    public FriendService(Db db) {
        this.db = db;
    }

    private String low(String a, String b) {
        return a.compareTo(b) < 0 ? a : b;
    }

    private String high(String a, String b) {
        return a.compareTo(b) > 0 ? a : b;
    }

    public boolean friends(String a, String b) {
        return db.exists("""
                SELECT id FROM friendship
                WHERE low_user=? AND high_user=?
                  AND status='ACCEPTED'
                """,
                low(a, b),
                high(a, b)
        );
    }

    @Transactional
    public Map<String, Object> request(
            String actor,
            FriendRequest input
    ) {
        boolean byId = input.loginId() != null
                && !input.loginId().isBlank();

        boolean byQr = input.qrToken() != null
                && !input.qrToken().isBlank();

        if (byId == byQr) {
            throw ApiException.bad(
                    "ONE_FRIEND_IDENTIFIER",
                    "아이디 또는 QR 토큰 하나만 보내주세요."
            );
        }

        String target = (String) db.one(
                byId
                        ? "SELECT id FROM app_user WHERE login_id=?"
                        : "SELECT id FROM app_user WHERE qr_token=?",
                byId
                        ? input.loginId().toLowerCase(Locale.ROOT)
                        : input.qrToken()
        ).get("id");

        if (target.equals(actor)) {
            throw ApiException.bad(
                    "SELF_FRIEND",
                    "본인에게 친구 요청을 보낼 수 없어요."
            );
        }

        db.lockUsers(List.of(actor, target));

        if (db.exists("""
                SELECT id FROM friendship
                WHERE low_user=? AND high_user=?
                """,
                low(actor, target),
                high(actor, target)
        )) {
            throw new ApiException(
                    409,
                    "FRIEND_EXISTS",
                    "이미 친구이거나 대기 중인 요청이 있어요."
            );
        }

        String requestId = Db.id();

        db.jdbc.update("""
                INSERT INTO friendship(
                    id,low_user,high_user,
                    requester,status,created_at
                ) VALUES (?,?,?,?,?,?)
                """,
                requestId,
                low(actor, target),
                high(actor, target),
                actor,
                "PENDING",
                Db.now()
        );

        return Map.of(
                "requestId", requestId,
                "status", "PENDING"
        );
    }

    public List<Map<String, Object>> list(String actor) {
        return db.jdbc.queryForList("""
                SELECT u.id,u.login_id,u.resting
                FROM friendship f
                JOIN app_user u ON u.id=
                    CASE WHEN f.low_user=?
                         THEN f.high_user
                         ELSE f.low_user END
                WHERE (f.low_user=? OR f.high_user=?)
                  AND f.status='ACCEPTED'
                ORDER BY u.login_id
                """,
                actor, actor, actor
        );
    }

    public List<Map<String, Object>> pending(String actor) {
        return db.jdbc.queryForList("""
                SELECT f.id,f.requester,u.login_id,f.created_at
                FROM friendship f
                JOIN app_user u ON u.id=f.requester
                WHERE (f.low_user=? OR f.high_user=?)
                  AND f.status='PENDING'
                ORDER BY f.created_at DESC
                """,
                actor, actor
        );
    }

    @Transactional
    public void decide(
            String actor,
            String requestId,
            boolean accept
    ) {
        var row = db.one("""
                SELECT * FROM friendship
                WHERE id=? AND (low_user=? OR high_user=?)
                """,
                requestId, actor, actor
        );

        db.lockUsers(List.of(
                (String) row.get("low_user"),
                (String) row.get("high_user")
        ));

        row = db.one(
                "SELECT * FROM friendship WHERE id=?",
                requestId
        );

        if (!"PENDING".equals(row.get("status"))
                || actor.equals(row.get("requester"))) {
            throw new ApiException(
                    409,
                    "INVALID_FRIEND_REQUEST",
                    "받은 대기 요청만 처리할 수 있어요."
            );
        }

        if (accept) {
            db.jdbc.update(
                    "UPDATE friendship SET status='ACCEPTED' WHERE id=?",
                    requestId
            );
        } else {
            db.jdbc.update(
                    "DELETE FROM friendship WHERE id=?",
                    requestId
            );
        }
    }

    @Transactional
    public void delete(String actor, String target) {
        db.lockUsers(List.of(actor, target));

        int deleted = db.jdbc.update("""
                DELETE FROM friendship
                WHERE low_user=? AND high_user=?
                  AND status='ACCEPTED'
                """,
                low(actor, target),
                high(actor, target)
        );

        if (deleted == 0) {
            throw ApiException.missing();
        }
    }
}