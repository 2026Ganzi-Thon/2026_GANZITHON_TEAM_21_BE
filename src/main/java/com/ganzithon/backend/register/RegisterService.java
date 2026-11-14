package com.ganzithon.backend.register;

import com.ganzithon.backend.api.ApiException;
import com.ganzithon.backend.api.ApiModels.Credentials;
import com.ganzithon.backend.common.Db;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RegisterService {

    private final Db db;

    private final BCryptPasswordEncoder encoder =
            new BCryptPasswordEncoder(12);

    private final SecureRandom random = new SecureRandom();
    private final long sessionHours;
    private final boolean demo;
    private final Set<String> demoLogins;

    private final Map<String, Attempt> attempts =
            new ConcurrentHashMap<>();

    private record Attempt(int count, long until) {
    }

    public RegisterService(
            Db db,
            @Value("${emori.session-hours}") long sessionHours,
            @Value("${emori.demo.enabled}") boolean demo,
            @Value("${emori.demo.premium-logins}") String logins
    ) {
        this.db = db;
        this.sessionHours = sessionHours;
        this.demo = demo;

        this.demoLogins = new HashSet<>(
                Arrays.asList(
                        logins.toLowerCase(Locale.ROOT).split(",")
                )
        );
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }

    private String normalize(String loginId) {
        return loginId.toLowerCase(Locale.ROOT);
    }

    // 단일 서버 MVP용 요청 제한
    public void throttle(String key) {
        long now = Db.now();

        attempts.entrySet()
                .removeIf(entry -> entry.getValue().until() < now);

        if (attempts.size() > 10000) {
            throw new ApiException(
                    429, "RATE_LIMITED", "잠시 후 다시 시도해주세요."
            );
        }

        Attempt attempt = attempts.compute(key, (k, previous) -> {
            if (previous == null || previous.until() < now) {
                return new Attempt(1, now + 600000);
            }

            return new Attempt(
                    previous.count() + 1,
                    previous.until()
            );
        });

        if (attempt.count() > 20) {
            throw new ApiException(
                    429,
                    "RATE_LIMITED",
                    "요청이 많아요. 10분 후 다시 시도해주세요."
            );
        }
    }

    private void validatePassword(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiException.bad(
                    "PASSWORD_TOO_LONG",
                    "비밀번호는 10글자 이내로 만들어주세요."
            );
        }
    }

    @Transactional
    public Map<String, Object> signup(Credentials input) {
        validatePassword(input.password());

        String login = normalize(input.loginId());

        if (db.exists(
                "SELECT id FROM app_user WHERE login_id=?",
                login
        )) {
            throw new ApiException(
                    409,
                    "LOGIN_ID_TAKEN",
                    "이미 사용 중인 아이디예요."
            );
        }

        String userId = Db.id();

        // 실제 결제가 아닌 개발 시연용 권한 부여
        boolean premium = demo && demoLogins.contains(login);

        db.jdbc.update("""
                INSERT INTO app_user(
                    id,login_id,password_hash,
                    qr_token,premium,created_at
                ) VALUES (?,?,?,?,?,?)
                """,
                userId,
                login,
                encoder.encode(input.password()),
                randomToken(),
                premium,
                Db.now()
        );

        return issue(userId);
    }

    @Transactional
    public Map<String, Object> login(Credentials input) {
        validatePassword(input.password());

        String login = normalize(input.loginId());

        var rows = db.jdbc.queryForList(
                "SELECT id,password_hash FROM app_user WHERE login_id=?",
                login
        );

        String passwordHash = rows.isEmpty()
                ? "$2a$12$M7IDK.p7IZm1TxKQha1nWeHtpmDRdRnrjjVj5hbjjgmiZcW1XHk6S"
                : (String) rows.getFirst().get("password_hash");

        boolean matches = encoder.matches(
                input.password(),
                passwordHash
        );

        if (!matches || rows.isEmpty()) {
            throw new ApiException(
                    401,
                    "INVALID_CREDENTIALS",
                    "아이디 또는 비밀번호를 확인해주세요."
            );
        }

        return issue((String) rows.getFirst().get("id"));
    }

    private Map<String, Object> issue(String userId) {
        String token = randomToken();

        // 1시간 후 로그인 만료로 임시 설정
        long expiresAt = Db.now()
                + sessionHours * 3600000L;

        db.jdbc.update("""
                INSERT INTO auth_session(
                    token_hash,user_id,expires_at
                ) VALUES (?,?,?)
                """,
                Db.hash(token),
                userId,
                expiresAt
        );

        return Map.of(
                "accessToken", token,
                "tokenType", "Bearer",
                "expiresAt", expiresAt,
                "user", db.user(userId)
        );
    }

    public String authenticate(String authorization) {
        if (authorization == null
                || !authorization.startsWith("Bearer ")
                || authorization.length() > 200) {
            throw new ApiException(
                    401,
                    "UNAUTHORIZED",
                    "로그인이 필요해요."
            );
        }

        var rows = db.jdbc.queryForList("""
                SELECT user_id FROM auth_session
                WHERE token_hash=? AND expires_at>?
                """,
                Db.hash(authorization.substring(7)),
                Db.now()
        );

        if (rows.isEmpty()) {
            throw new ApiException(
                    401,
                    "UNAUTHORIZED",
                    "다시 로그인해주세요."
            );
        }

        return (String) rows.getFirst().get("user_id");
    }

    public void logout(String authorization) {
        db.jdbc.update(
                "DELETE FROM auth_session WHERE token_hash=?",
                Db.hash(authorization.substring(7))
        );
    }
}
