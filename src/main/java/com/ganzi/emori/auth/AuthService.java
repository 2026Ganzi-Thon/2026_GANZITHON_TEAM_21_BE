package com.ganzi.emori.auth;

import com.ganzi.emori.api.ApiException;
import com.ganzi.emori.auth.AuthModels.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AuthService {
    private static final Pattern LOGIN_ID = Pattern.compile("[a-z0-9_]{4,20}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private final JdbcTemplate jdbc;
    private final PasswordHasher passwords;
    private final SecureRandom random = new SecureRandom();
    private final long accessSeconds;
    private final long refreshSeconds;
    private final String dummyHash;
    private final Clock clock;

    public AuthService(JdbcTemplate jdbc, PasswordHasher passwords, Clock clock,
                       @Value("${emori.auth.access-seconds}") long accessSeconds,
                       @Value("${emori.auth.refresh-seconds}") long refreshSeconds) {
        if (accessSeconds < 1 || refreshSeconds < accessSeconds) {
            throw new IllegalArgumentException("Invalid session lifetimes");
        }
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.clock = clock;
        this.accessSeconds = accessSeconds;
        this.refreshSeconds = refreshSeconds;
        this.dummyHash = passwords.hash("dummy-password-for-login-timing");
    }

    public Availability availability(String input) {
        String loginId = normalize(input);
        return new Availability(loginId, jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE login_id=?", Integer.class, loginId) == 0);
    }

    @Transactional
    public AuthResponse signup(SignupRequest request) {
        if (request == null) throw ApiException.invalid("가입 정보를 입력해주세요.");
        String loginId = normalize(request.loginId());
        validatePassword(request.password());
        String nickname = request.nickname() == null ? loginId : nickname(request.nickname());
        UUID id = UUID.randomUUID();
        OffsetDateTime now = utcNow();
        try {
            jdbc.update("INSERT INTO app_user(id,login_id,password_hash,nickname,adult_status,created_at,updated_at) VALUES (?,?,?,?,'UNKNOWN',?,?)",
                    id, loginId, passwords.hash(request.password()), nickname, now, now);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "LOGIN_ID_IN_USE", "이미 사용 중인 아이디입니다.");
        }
        jdbc.update("INSERT INTO user_settings(user_id,updated_at) VALUES (?,?)", id, now);
        return new AuthResponse(me(id), createSession(id));
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        if (request == null) throw ApiException.invalid("로그인 정보를 입력해주세요.");
        String loginId = normalize(request.loginId());
        validatePassword(request.password());
        List<UserPassword> found = jdbc.query("SELECT id,password_hash FROM app_user WHERE login_id=?",
                (rs, n) -> new UserPassword(rs.getObject("id", UUID.class), rs.getString("password_hash")), loginId);
        String hash = found.isEmpty() ? dummyHash : found.getFirst().hash();
        if (!passwords.matches(request.password(), hash) || found.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        UUID id = found.getFirst().id();
        return new AuthResponse(me(id), createSession(id));
    }

    @Transactional
    public Session refresh(RefreshRequest request) {
        if (request == null || !validToken(request.refreshToken())) throw ApiException.unauthorized();
        String previousHash = tokenHash(request.refreshToken());
        List<UUID> sessions = jdbc.query("SELECT id FROM auth_session WHERE refresh_token_hash=? FOR UPDATE",
                (rs, n) -> rs.getObject("id", UUID.class), previousHash);
        if (sessions.isEmpty()) throw ApiException.unauthorized();
        UUID sessionId = sessions.getFirst();
        OffsetDateTime now = utcNow();
        String access = token();
        String refresh = token();
        int updated = jdbc.update("""
                UPDATE auth_session SET access_token_hash=?,refresh_token_hash=?,
                    access_expires_at=?,refresh_expires_at=?,rotation_number=rotation_number+1,updated_at=?
                WHERE id=? AND refresh_token_hash=? AND revoked_at IS NULL AND refresh_expires_at>?
                """, tokenHash(access), tokenHash(refresh), now.plusSeconds(accessSeconds),
                now.plusSeconds(refreshSeconds), now, sessionId, previousHash, now);
        if (updated != 1) throw ApiException.unauthorized();
        return new Session(access, refresh, accessSeconds);
    }

    @Transactional
    public void logout(String bearer) {
        String hash = tokenHash(bearerToken(bearer));
        jdbc.update("DELETE FROM device_registration WHERE session_id IN (SELECT id FROM auth_session WHERE access_token_hash=?)",hash);
        jdbc.update("UPDATE auth_session SET revoked_at=?,updated_at=? WHERE access_token_hash=? AND revoked_at IS NULL",
                utcNow(), utcNow(), hash);
    }

    public UUID authenticate(String bearer) {
        String hash = tokenHash(bearerToken(bearer));
        List<UUID> found = jdbc.query("""
                SELECT user_id FROM auth_session
                WHERE access_token_hash=? AND revoked_at IS NULL AND access_expires_at>?
                """, (rs, n) -> rs.getObject("user_id", UUID.class), hash, utcNow());
        if (found.isEmpty()) throw ApiException.unauthorized();
        return found.getFirst();
    }

    public Me me(UUID id) {
        List<Me> found = jdbc.query("SELECT id,login_id,nickname,adult_status FROM app_user WHERE id=?",
                (rs, n) -> new Me(rs.getObject("id", UUID.class).toString(), rs.getString("login_id"),
                        rs.getString("nickname"), rs.getString("login_id"), rs.getString("adult_status")), id);
        if (found.isEmpty()) throw ApiException.unauthorized();
        return found.getFirst();
    }

    @Transactional
    public Me updateProfile(UUID id, ProfileRequest request) {
        if (request == null) throw ApiException.invalid("닉네임을 입력해주세요.");
        jdbc.update("UPDATE app_user SET nickname=?,updated_at=? WHERE id=?", nickname(request.nickname()), utcNow(), id);
        return me(id);
    }

    @Transactional
    public Me updateBirthDate(UUID id, BirthDateRequest request) {
        if (request == null || request.birthDate() == null ||
                !request.birthDate().matches("\\d{4}-\\d{2}-\\d{2}"))
            throw ApiException.invalid("birthDate must be YYYY-MM-DD");
        LocalDate birthDate;
        try { birthDate = LocalDate.parse(request.birthDate()); }
        catch (DateTimeParseException e) { throw ApiException.invalid("Invalid birthDate"); }
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        if (birthDate.isAfter(today)) throw ApiException.invalid("birthDate cannot be in the future");
        // A leap-day birthday reaches the yearly age on March 1 in non-leap years.
        String reported = isAdult(birthDate, today) ? "SELF_REPORTED_19_PLUS" : "SELF_REPORTED_UNDER_19";
        jdbc.update("""
                UPDATE app_user SET birth_date=?,adult_status=CASE WHEN adult_status='VERIFIED_19_PLUS'
                    THEN adult_status ELSE ? END,adult_checked_at=?,updated_at=? WHERE id=?
                """, birthDate, reported, utcNow(), utcNow(), id);
        return me(id);
    }

    static boolean isAdult(LocalDate birthDate, LocalDate today) {
        LocalDate nineteenth = birthDate.plusYears(19);
        if (birthDate.getMonthValue() == 2 && birthDate.getDayOfMonth() == 29 && !nineteenth.isLeapYear())
            nineteenth = LocalDate.of(nineteenth.getYear(), 3, 1);
        return !today.isBefore(nineteenth);
    }

    private Session createSession(UUID userId) {
        String access = token();
        String refresh = token();
        OffsetDateTime now = utcNow();
        jdbc.update("""
                INSERT INTO auth_session(id,user_id,access_token_hash,refresh_token_hash,
                    access_expires_at,refresh_expires_at,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), userId, tokenHash(access), tokenHash(refresh),
                now.plusSeconds(accessSeconds), now.plusSeconds(refreshSeconds), now, now);
        return new Session(access, refresh, accessSeconds);
    }

    private String normalize(String input) {
        if (input == null) throw ApiException.invalid("아이디를 입력해주세요.");
        String result = input.toLowerCase(Locale.ROOT);
        if (!LOGIN_ID.matcher(result).matches()) throw ApiException.invalid("아이디 형식을 확인해주세요.");
        return result;
    }

    private void validatePassword(String password) {
        if (password == null) throw ApiException.invalid("비밀번호를 입력해주세요.");
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 8 || bytes > 72) throw ApiException.invalid("비밀번호는 UTF-8 기준 8~72바이트여야 합니다.");
    }

    private String nickname(String input) {
        if (input == null) throw ApiException.invalid("닉네임을 입력해주세요.");
        String result = input.trim();
        if (result.isEmpty() || result.codePointCount(0, result.length()) > 20) {
            throw ApiException.invalid("닉네임은 1~20자여야 합니다.");
        }
        return result;
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean validToken(String value) { return value != null && TOKEN.matcher(value).matches(); }

    private String bearerToken(String header) {
        if (header == null || !header.startsWith("Bearer ")) throw ApiException.unauthorized();
        String value = header.substring(7);
        if (!validToken(value)) throw ApiException.unauthorized();
        return value;
    }

    private String tokenHash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException("Token hashing unavailable", e);
        }
    }

    private OffsetDateTime utcNow() { return Instant.now().atOffset(ZoneOffset.UTC); }

    private record UserPassword(UUID id, String hash) {}
}
