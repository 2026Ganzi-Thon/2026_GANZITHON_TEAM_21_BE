package com.ganzi.emori;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:auth_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class AuthHttpTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void twoAccountsAndSessionLifecycle() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String first = "alice_" + suffix;
        String second = "bob_" + suffix;

        JsonNode a = postJson("/api/v1/auth/signup", "{\"loginId\":\"" + first.toUpperCase() + "\",\"password\":\"secret123\"}", 201);
        JsonNode b = postJson("/api/v1/auth/signup", "{\"loginId\":\"" + second + "\",\"password\":\"secret456\",\"nickname\":\"  친구  \"}", 201);
        assertThat(a.at("/user/loginId").asText()).isEqualTo(first);
        assertThat(a.at("/user/nickname").asText()).isEqualTo(first);
        assertThat(a.at("/user/friendCode").asText()).isEqualTo(first);
        assertThat(a.at("/user/adultStatus").asText()).isEqualTo("UNKNOWN");
        String storedPassword = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE login_id=?", String.class, first);
        assertThat(storedPassword).startsWith("pbkdf2-sha256$").doesNotContain("secret123");
        assertThat(b.at("/user/nickname").asText()).isEqualTo("친구");
        assertThat(getAvailability(first)).isFalse();

        String signupAccess = a.at("/session/accessToken").asText();
        assertThat(getMe(signupAccess, 200).at("/loginId").asText()).isEqualTo(first);
        JsonNode changed = json.readTree(mvc.perform(put("/api/v1/me/profile")
                .header("Authorization", "Bearer " + signupAccess).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"  새이름  \"}"))
                .andReturn().getResponse().getContentAsString());
        assertThat(changed.path("nickname").asText()).isEqualTo("새이름");

        JsonNode login = postJson("/api/v1/auth/login", "{\"loginId\":\"" + first + "\",\"password\":\"secret123\"}", 200);
        String oldAccess = login.at("/session/accessToken").asText();
        String oldRefresh = login.at("/session/refreshToken").asText();
        JsonNode rotated = postJson("/api/v1/auth/refresh", "{\"refreshToken\":\"" + oldRefresh + "\"}", 200);
        assertThat(rotated.path("expiresIn").asLong()).isPositive();
        assertThat(getMe(oldAccess, 401).at("/error/code").asText()).isEqualTo("INVALID_SESSION");
        assertThat(postJson("/api/v1/auth/refresh", "{\"refreshToken\":\"" + oldRefresh + "\"}", 401)
                .at("/error/code").asText()).isEqualTo("INVALID_SESSION");

        String newAccess = rotated.path("accessToken").asText();
        String newRefresh = rotated.path("refreshToken").asText();
        assertThat(getMe(newAccess, 200).path("nickname").asText()).isEqualTo("새이름");
        var invalidLogout = mvc.perform(post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + newAccess)
                .contentType(MediaType.APPLICATION_JSON).content("{\"unexpected\":1}"))
                .andReturn().getResponse();
        assertThat(invalidLogout.getStatus()).isEqualTo(422);
        assertThat(getMe(newAccess, 200).path("loginId").asText()).isEqualTo(first);
        assertThat(mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + newAccess)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus()).isEqualTo(204);
        assertThat(getMe(newAccess, 401).at("/error/code").asText()).isEqualTo("INVALID_SESSION");
        assertThat(postJson("/api/v1/auth/refresh", "{\"refreshToken\":\"" + newRefresh + "\"}", 401)
                .at("/error/code").asText()).isEqualTo("INVALID_SESSION");
    }

    @Test
    void rejectsDuplicatesBadInputAndBadCredentials() throws Exception {
        String login = "dup_" + UUID.randomUUID().toString().substring(0, 8);
        postJson("/api/v1/auth/signup", "{\"loginId\":\"" + login + "\",\"password\":\"secret123\"}", 201);
        assertThat(postJson("/api/v1/auth/signup", "{\"loginId\":\"" + login.toUpperCase() + "\",\"password\":\"secret123\"}", 409)
                .at("/error/code").asText()).isEqualTo("LOGIN_ID_IN_USE");
        assertThat(postJson("/api/v1/auth/signup", "{\"loginId\":\"bad!\",\"password\":\"short\"}", 422)
                .at("/error/code").asText()).isEqualTo("INVALID_INPUT");
        assertThat(postJson("/api/v1/auth/login", "{\"loginId\":\"" + login + "\",\"password\":\"wrongpass\"}", 401)
                .at("/error/code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(getMe("bad-token", 401).at("/error/code").asText()).isEqualTo("INVALID_SESSION");
        assertThat(postJson("/api/v1/auth/signup", "{bad json", 400).at("/error/code").asText()).isEqualTo("INVALID_JSON");
        assertThat(postJson("/api/v1/auth/signup", "{\"loginId\":\"othername\",\"password\":\"secret123\",\"extra\":1}", 422)
                .at("/error/code").asText()).isEqualTo("INVALID_INPUT");
        assertThat(postJson("/api/v1/auth/signup", "{\"loginId\":\"othername\"}", 422)
                .at("/error/code").asText()).isEqualTo("INVALID_INPUT");
    }

    @Test
    void concurrentRefreshAllowsOneRotation() throws Exception {
        String login = "race_" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode signed = postJson("/api/v1/auth/signup", "{\"loginId\":\"" + login + "\",\"password\":\"secret123\"}", 201);
        String refresh = signed.at("/session/refreshToken").asText();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> request = () -> {
                start.await();
                return mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                        .andReturn().getResponse().getStatus();
            };
            var one = pool.submit(request);
            var two = pool.submit(request);
            start.countDown();
            assertThat(java.util.List.of(one.get(), two.get())).containsExactlyInAnyOrder(200, 401);
        }
    }

    @Test
    void expiredAccessCanStillRefreshBeforeRefreshExpiry() throws Exception {
        String login = "expire_" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode signed = postJson("/api/v1/auth/signup", "{\"loginId\":\"" + login + "\",\"password\":\"secret123\"}", 201);
        String access = signed.at("/session/accessToken").asText();
        String refresh = signed.at("/session/refreshToken").asText();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(access.getBytes(StandardCharsets.US_ASCII)));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("UPDATE auth_session SET created_at=?,access_expires_at=? WHERE access_token_hash=?",
                now.minusDays(2), now.minusDays(1), hash);
        assertThat(getMe(access, 401).at("/error/code").asText()).isEqualTo("INVALID_SESSION");
        JsonNode rotated = postJson("/api/v1/auth/refresh", "{\"refreshToken\":\"" + refresh + "\"}", 200);
        assertThat(getMe(rotated.path("accessToken").asText(), 200).path("loginId").asText()).isEqualTo(login);
    }

    private JsonNode postJson(String path, String body, int status) throws Exception {
        var response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(status);
        return json.readTree(response.getContentAsString());
    }

    private JsonNode getMe(String token, int status) throws Exception {
        var response = mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(status);
        return json.readTree(response.getContentAsString());
    }

    private boolean getAvailability(String loginId) throws Exception {
        var response = mvc.perform(get("/api/v1/auth/login-id-availability").param("loginId", loginId)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return json.readTree(response.getContentAsString()).path("available").asBoolean();
    }
}
