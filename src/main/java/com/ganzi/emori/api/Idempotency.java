package com.ganzi.emori.api;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Component
public class Idempotency {
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final IdempotencyResponseCipher cipher;
    private final ConcurrentHashMap<String, Boolean> active = new ConcurrentHashMap<>();

    public Idempotency(JdbcTemplate jdbc, ObjectMapper json, IdempotencyResponseCipher cipher) {
        this.jdbc = jdbc;
        this.json = json;
        this.cipher = cipher;
    }

    public UUID key(String value) {
        if (value == null) throw ApiException.invalid("Idempotency-Key UUID가 필요합니다.");
        try { if (!UUID_TEXT.matcher(value).matches()) throw new IllegalArgumentException(); return UUID.fromString(value); }
        catch (RuntimeException e) { throw ApiException.invalid("Idempotency-Key UUID가 필요합니다."); }
    }

    // The gate supplies REQUEST_IN_PROGRESS for concurrent requests in this process.
    // The database uniqueness and caller's user-row lock remain the cross-process guard.
    public String gate(UUID user, String method, String path, UUID key, Supplier<String> work) {
        String scope = user + "|" + method + "|" + path + "|" + key;
        if (active.putIfAbsent(scope, Boolean.TRUE) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "같은 요청을 처리 중입니다.");
        }
        try { return work.get(); }
        finally { active.remove(scope); }
    }

    // Call only inside a transaction after locking the actor's app_user row.
    public String execute(UUID user, String method, String path, UUID key, String canonicalBody, Supplier<?> work) {
        return execute(user, method, path, key, canonicalBody, 201, work);
    }

    public String execute(UUID user, String method, String path, UUID key, String canonicalBody,
                          int httpStatus, Supplier<?> work) {
        String requestHash = DbSupport.hash(canonicalBody);
        List<Stored> found = jdbc.query("""
                SELECT request_hash,response_json,expires_at FROM idempotency_request
                WHERE user_id=? AND method=? AND resource_path=? AND request_key=?
                """, (rs, n) -> new Stored(rs.getString(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class)),
                user, method, path, key);
        OffsetDateTime now = DbSupport.now();
        if (!found.isEmpty()) {
            Stored old = found.getFirst();
            if (old.expiresAt().isAfter(now)) {
                if (!old.requestHash().equals(requestHash)) {
                    throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "같은 키의 요청 내용이 다릅니다.");
                }
                return cipher.reveal(old.responseJson());
            }
            jdbc.update("DELETE FROM idempotency_request WHERE user_id=? AND method=? AND resource_path=? AND request_key=?",
                    user, method, path, key);
        }
        String response = json.writeValueAsString(work.get());
        OffsetDateTime done = DbSupport.now();
        jdbc.update("""
                INSERT INTO idempotency_request(id,user_id,method,resource_path,request_key,request_hash,
                    http_status,response_json,completed_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), user, method, path, key, requestHash, httpStatus,
                cipher.protect(response), done, done.plusHours(24));
        return response;
    }

    private record Stored(String requestHash, String responseJson, OffsetDateTime expiresAt) {}
}
