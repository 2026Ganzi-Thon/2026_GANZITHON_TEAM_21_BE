package com.ganzi.emori;

import com.ganzi.emori.api.DbSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@EnabledIfEnvironmentVariable(named = "EMORI_POSTGRES_TEST", matches = "true")
@ActiveProfiles("postgres")
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
class Stage2PostgresRaceTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void oppositeFriendRequestsAndDifferentDispatchKeysRemainUnique() throws Exception {
        User a = signup("pga");
        User b = signup("pgb");
        CountDownLatch requestStart = new CountDownLatch(1);
        Result one;
        Result two;
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<Result> fromA = () -> {
                requestStart.await();
                return request(a.token(), "/friend-requests", null, "{\"friendCode\":\"" + b.login() + "\"}");
            };
            Callable<Result> fromB = () -> {
                requestStart.await();
                return request(b.token(), "/friend-requests", null, "{\"friendCode\":\"" + a.login() + "\"}");
            };
            var futureA = workers.submit(fromA);
            var futureB = workers.submit(fromB);
            requestStart.countDown();
            one = futureA.get();
            two = futureB.get();
        }
        assertThat(List.of(one.status(), two.status())).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM friend_request WHERE pending_pair_key=?",
                Integer.class, DbSupport.pair(a.id(), b.id()))).isEqualTo(1);
        Result winner = one.status() == 201 ? one : two;
        String recipientToken = one.status() == 201 ? b.token() : a.token();
        var accepted = mvc.perform(put("/api/v1/friend-requests/{id}/decision", winner.body().path("id").asText())
                .header("Authorization", "Bearer " + recipientToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"ACCEPT\"}"))
                .andReturn().getResponse();
        assertThat(accepted.getStatus()).as(accepted.getContentAsString()).isEqualTo(200);

        Result draft = request(a.token(), "/orbs", UUID.randomUUID().toString(),
                "{\"emotionIds\":[\"blue_03\",\"yellow_01\"]}");
        assertThat(draft.status()).isEqualTo(201);
        UUID orbId = UUID.fromString(draft.body().at("/orb/id").asText());
        String path = "/orbs/" + orbId + "/dispatches";
        String payload = "{\"route\":\"FRIEND\",\"friendId\":\"" + b.id() + "\"}";
        CountDownLatch dispatchStart = new CountDownLatch(1);
        Result first;
        Result second;
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<Result> send = () -> {
                dispatchStart.await();
                return request(a.token(), path, UUID.randomUUID().toString(), payload);
            };
            var futureA = workers.submit(send);
            var futureB = workers.submit(send);
            dispatchStart.countDown();
            first = futureA.get();
            second = futureB.get();
        }
        assertThat(List.of(first.status(), second.status())).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch WHERE orb_id=?", Integer.class, orbId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receipt WHERE dispatch_id=(SELECT id FROM dispatch WHERE orb_id=?)",
                Integer.class, orbId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM emotion_record WHERE dispatch_id=(SELECT id FROM dispatch WHERE orb_id=?)",
                Integer.class, orbId)).isEqualTo(2);
    }

    private User signup(String prefix) throws Exception {
        String login = prefix + UUID.randomUUID().toString().substring(0, 8);
        var response = mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + login + "\",\"password\":\"secret123\"}"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        JsonNode body = json.readTree(response.getContentAsString());
        return new User(UUID.fromString(body.at("/user/id").asText()), login,
                body.at("/session/accessToken").asText());
    }

    private Result request(String token, String path, String key, String body) throws Exception {
        var builder = post("/api/v1" + path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) builder.header("Idempotency-Key", key);
        var response = mvc.perform(builder).andReturn().getResponse();
        return new Result(response.getStatus(), json.readTree(response.getContentAsString()));
    }

    private record User(UUID id, String login, String token) {}
    private record Result(int status, JsonNode body) {}
}
