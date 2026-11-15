package com.ganzi.emori.api;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class DbSupport {
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private final JdbcTemplate jdbc;
    public DbSupport(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void lockUsers(UUID... users) {
        List<UUID> sorted = java.util.Arrays.stream(users).distinct().sorted(DbSupport::compareIds).toList();
        for (UUID id : sorted) {
            if (jdbc.query("SELECT id FROM app_user WHERE id=? FOR UPDATE", (rs, n) -> rs.getObject(1, UUID.class), id).isEmpty()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "대상을 찾을 수 없습니다.");
            }
        }
    }

    public static UUID pathId(String value) {
        try { if (value == null || !UUID_TEXT.matcher(value).matches()) throw new IllegalArgumentException(); return UUID.fromString(value); }
        catch (RuntimeException e) { throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "대상을 찾을 수 없습니다."); }
    }

    public static UUID requiredId(String value) {
        try { if (value == null || !UUID_TEXT.matcher(value).matches()) throw new IllegalArgumentException(); return UUID.fromString(value); }
        catch (RuntimeException e) { throw ApiException.invalid("ID 형식을 확인해주세요."); }
    }

    public static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }

    public static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("Hash unavailable", e); }
    }

    public static String pair(UUID a, UUID b) {
        return compareIds(a, b) < 0 ? a + ":" + b : b + ":" + a;
    }

    // PostgreSQL and H2 compare UUID bytes as unsigned values; UUID.compareTo uses signed longs.
    public static int compareIds(UUID a, UUID b) {
        int first = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return first != 0 ? first : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }

    public static UUID low(UUID a, UUID b) { return compareIds(a, b) < 0 ? a : b; }
    public static UUID high(UUID a, UUID b) { return compareIds(a, b) > 0 ? a : b; }

    public static int limit(Integer input) {
        int value = input == null ? 20 : input;
        if (value < 1 || value > 100) throw ApiException.invalid("limit은 1~100이어야 합니다.");
        return value;
    }

    public static Cursor cursor(String token, String scope) {
        if (token == null) return null;
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 3 || !scope.equals(parts[0])) throw new IllegalArgumentException();
            return new Cursor(OffsetDateTime.parse(parts[1]), UUID.fromString(parts[2]));
        } catch (RuntimeException e) { throw ApiException.invalid("cursor를 확인해주세요."); }
    }

    public static String cursor(String scope, OffsetDateTime at, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (scope + "|" + at.withOffsetSameInstant(ZoneOffset.UTC) + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    public record Cursor(OffsetDateTime at, UUID id) {}
    public record Page<T>(List<T> items, String nextCursor) {}
}
