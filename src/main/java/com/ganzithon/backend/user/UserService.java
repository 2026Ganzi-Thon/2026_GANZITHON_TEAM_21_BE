package com.ganzithon.backend.user;

import com.ganzithon.backend.common.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class UserService {

    private final Db db;

    public UserService(Db db) {
        this.db = db;
    }

    public Map<String, Object> me(String userId) {
        return db.user(userId);
    }

    public Map<String, Object> qr(String userId) {
        Object token = db.one(
                "SELECT qr_token FROM app_user WHERE id=?",
                userId
        ).get("qr_token");

        return Map.of(
                "qrToken", token,
                "format", "emori:friend:<qrToken>"
        );
    }

    public Map<String, Object> search(String loginId) {
        return db.one(
                "SELECT id,login_id FROM app_user WHERE login_id=?",
                loginId.toLowerCase(Locale.ROOT)
        );
    }

    @Transactional
    public Map<String, Object> setRest(
            String userId,
            boolean enabled
    ) {
        db.lockUsers(List.of(userId));

        db.jdbc.update(
                "UPDATE app_user SET resting=? WHERE id=?",
                enabled,
                userId
        );

        if (enabled) {
            db.jdbc.update("""
                    UPDATE push_outbox
                    SET status='SUPPRESSED'
                    WHERE user_id=? AND status='PENDING'
                    """,
                    userId
            );
        }

        return db.user(userId);
    }
}