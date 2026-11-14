package com.ganzithon.backend.common;

import com.ganzithon.backend.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Component
public class Db {

    public final JdbcTemplate jdbc;

    public Db(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String id() {
        return UUID.randomUUID().toString();
    }

    public static long now() {
        return System.currentTimeMillis();
    }

    public static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);

        if (rows.isEmpty()) {
            throw ApiException.missing();
        }

        return rows.getFirst();
    }

    public boolean exists(String sql, Object... args) {
        return !jdbc.queryForList(sql, args).isEmpty();
    }

    // 전송·휴식·친구 상태 변경이 충돌하지 않도록 잠금
    public void lockUsers(Collection<String> ids) {
        ids.stream()
                .distinct()
                .sorted()
                .forEach(id -> one(
                        "SELECT id FROM app_user WHERE id=? FOR UPDATE",
                        id
                ));
    }

    public Map<String, Object> user(String id) {
        return one(
                "SELECT id,login_id,resting,premium FROM app_user WHERE id=?",
                id
        );
    }

    public static boolean bool(
            Map<String, Object> row,
            String key
    ) {
        return Boolean.TRUE.equals(row.get(key));
    }
}
