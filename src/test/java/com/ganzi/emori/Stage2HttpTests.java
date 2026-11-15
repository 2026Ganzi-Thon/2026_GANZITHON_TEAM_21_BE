package com.ganzi.emori;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:stage2_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class Stage2HttpTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void friendDraftSelfAndFriendDispatchPreserveRecords() throws Exception {
        User a = signup("alice");
        User b = signup("bob");
        JsonNode request = call("POST", "/friend-requests", a.token(), null,
                "{\"friendCode\":\"" + b.login() + "\"}", 201);
        String requestId = request.path("id").asText();
        assertThat(request.at("/from/id").asText()).isEqualTo(a.id());
        assertThat(call("GET", "/friend-requests", b.token(), null, null, 200).path("items").size()).isEqualTo(1);
        assertThat(call("POST", "/friend-requests", b.token(), null,
                "{\"friendCode\":\"" + a.login() + "\"}", 409).at("/error/code").asText()).isEqualTo("RELATION_EXISTS");
        assertThat(call("PUT", "/friend-requests/" + requestId + "/decision", b.token(), null,
                "{\"decision\":\"ACCEPT\"}", 200).path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(call("PUT", "/friend-requests/" + requestId + "/decision", b.token(), null,
                "{\"decision\":\"ACCEPT\"}", 200).path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(call("PUT", "/friend-requests/" + requestId + "/decision", b.token(), null,
                "{\"decision\":\"DECLINE\"}", 409).at("/error/code").asText()).isEqualTo("REQUEST_CLOSED");
        assertThat(call("POST", "/friend-requests", a.token(), null,
                "{\"friendCode\":\"" + b.login() + "\"}", 409).at("/error/code").asText()).isEqualTo("RELATION_EXISTS");
        assertThat(call("GET", "/friends", a.token(), null, null, 200).at("/items/0/id").asText()).isEqualTo(b.id());

        JsonNode emotions = call("GET", "/catalog/emotions", a.token(), null, null, 200);
        assertThat(emotions.path("version").asText()).isEqualTo("1");
        assertThat(emotions.path("items").size()).isEqualTo(28);
        assertThat(call("GET", "/catalog/reactions", a.token(), null, null, 200).path("items").size()).isEqualTo(3);

        String createKey = UUID.randomUUID().toString();
        String singleBody = "{\"emotionIds\":[\"yellow_03\"]}";
        JsonNode single = call("POST", "/orbs", a.token(), createKey, singleBody, 201);
        String singleId = single.at("/orb/id").asText();
        assertThat(single.path("recordId").isNull()).isTrue();
        assertThat(single.at("/orb/emotions/0/word").asText()).isEqualTo("홀가분함");
        assertThat(call("POST", "/orbs", a.token(), createKey, singleBody, 201)).isEqualTo(single);
        assertThat(call("POST", "/orbs", a.token(), createKey, "{\"emotionIds\":[\"red_01\"]}", 409)
                .at("/error/code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(count("SELECT COUNT(*) FROM emotion_record WHERE owner_id=?", a.id())).isZero();
        assertThat(call("GET", "/orbs/" + singleId, a.token(), null, null, 200)
                .at("/orb/emotions/0/area").asText()).isEqualTo("기쁨·기대");
        assertThat(call("GET", "/orbs/" + singleId, b.token(), null, null, 404).at("/error/code").asText())
                .isEqualTo("NOT_FOUND");

        String selfKey = UUID.randomUUID().toString();
        String selfPath = "/orbs/" + singleId + "/dispatches";
        JsonNode saved = call("POST", selfPath, a.token(), selfKey, "{\"route\":\"SELF\"}", 201);
        assertThat(saved.at("/dispatch/status").asText()).isEqualTo("SAVED");
        assertThat(saved.at("/dispatch/targets").size()).isZero();
        assertThat(saved.path("recordId").asText()).isNotBlank();
        assertThat(call("POST", selfPath, a.token(), selfKey, "{\"route\":\"SELF\"}", 201)).isEqualTo(saved);
        assertThat(call("POST", selfPath, a.token(), UUID.randomUUID().toString(), "{\"route\":\"SELF\"}", 409)
                .at("/error/code").asText()).isEqualTo("ALREADY_DISPATCHED");
        assertThat(count("SELECT COUNT(*) FROM emotion_record WHERE dispatch_id=?", saved.at("/dispatch/id").asText())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM receipt WHERE dispatch_id=?", saved.at("/dispatch/id").asText())).isZero();

        assertThat(call("POST", "/orbs", a.token(), UUID.randomUUID().toString(),
                "{\"emotionIds\":[\"yellow_01\",\"yellow_02\"]}", 422).at("/error/code").asText())
                .isEqualTo("INVALID_INPUT");
        String mixedId = call("POST", "/orbs", a.token(), UUID.randomUUID().toString(),
                "{\"emotionIds\":[\"blue_03\",\"yellow_01\"]}", 201).at("/orb/id").asText();
        assertThat(call("GET", "/orbs/" + mixedId, a.token(), null, null, 200).at("/orb/emotions/0/word").asText())
                .isEqualTo("무기력");
        String friendPath = "/orbs/" + mixedId + "/dispatches";
        String friendBody = "{\"route\":\"FRIEND\",\"friendId\":\"" + b.id() + "\"}";
        String friendKey = UUID.randomUUID().toString();
        JsonNode sent = call("POST", friendPath, a.token(), friendKey, friendBody, 201);
        String dispatchId = sent.at("/dispatch/id").asText();
        assertThat(sent.at("/dispatch/status").asText()).isEqualTo("SENT");
        assertThat(sent.at("/dispatch/recipientCount").asInt()).isEqualTo(1);
        assertThat(sent.at("/dispatch/targets/0/counterpart/id").asText()).isEqualTo(b.id());
        assertThat(call("POST", friendPath, a.token(), friendKey, friendBody, 201)).isEqualTo(sent);
        assertThat(call("POST", friendPath, a.token(), UUID.randomUUID().toString(), friendBody, 409)
                .at("/error/code").asText()).isEqualTo("ALREADY_DISPATCHED");
        assertThat(count("SELECT COUNT(*) FROM emotion_record WHERE dispatch_id=?", dispatchId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM receipt WHERE dispatch_id=?", dispatchId)).isEqualTo(1);
        assertThat(call("GET", "/dispatches/" + dispatchId, b.token(), null, null, 404).at("/error/code").asText())
                .isEqualTo("NOT_FOUND");

        call("DELETE", "/friends/" + b.id(), a.token(), null, null, 204);
        call("DELETE", "/friends/" + b.id(), a.token(), null, null, 204);
        assertThat(call("GET", "/friends", b.token(), null, null, 200).path("items").size()).isZero();
        assertThat(count("SELECT COUNT(*) FROM emotion_record WHERE dispatch_id=?", dispatchId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM receipt WHERE dispatch_id=?", dispatchId)).isEqualTo(1);
        assertThat(call("GET", "/dispatches/" + dispatchId, a.token(), null, null, 200).path("id").asText())
                .isEqualTo(dispatchId);
        String later = call("POST", "/orbs", a.token(), UUID.randomUUID().toString(),
                "{\"emotionIds\":[\"green_01\"]}", 201).at("/orb/id").asText();
        assertThat(call("POST", "/orbs/" + later + "/dispatches", a.token(), UUID.randomUUID().toString(), friendBody, 409)
                .at("/error/code").asText()).isEqualTo("FRIENDSHIP_CHANGED");
    }

    @Test
    void cursorAndConcurrentOppositeRequest() throws Exception {
        User a = signup("cursor_a"); User b = signup("cursor_b"); User c = signup("cursor_c"); User d = signup("cursor_d");
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> first = () -> { start.await(); return status("POST", "/friend-requests", a.token(), null,
                    "{\"friendCode\":\"" + b.login() + "\"}"); };
            Callable<Integer> second = () -> { start.await(); return status("POST", "/friend-requests", b.token(), null,
                    "{\"friendCode\":\"" + a.login() + "\"}"); };
            var one = pool.submit(first); var two = pool.submit(second); start.countDown();
            assertThat(java.util.List.of(one.get(), two.get())).containsExactlyInAnyOrder(201, 409);
        }
        JsonNode fromC = call("POST", "/friend-requests", c.token(), null, "{\"friendCode\":\"" + a.login() + "\"}", 201);
        JsonNode fromD = call("POST", "/friend-requests", d.token(), null, "{\"friendCode\":\"" + a.login() + "\"}", 201);
        JsonNode page1 = call("GET", "/friend-requests?limit=1", a.token(), null, null, 200);
        assertThat(page1.path("items").size()).isEqualTo(1);
        String cursor = page1.path("nextCursor").asText();
        assertThat(cursor).isNotBlank();
        JsonNode page2 = call("GET", "/friend-requests?limit=1&cursor=" + cursor, a.token(), null, null, 200);
        assertThat(page2.path("items").size()).isEqualTo(1);
        assertThat(page2.at("/items/0/id").asText()).isNotEqualTo(page1.at("/items/0/id").asText());
        assertThat(call("GET", "/friend-requests?direction=OUTGOING&cursor=" + cursor, a.token(), null, null, 422)
                .at("/error/code").asText()).isEqualTo("INVALID_INPUT");
        call("PUT", "/friend-requests/" + fromC.path("id").asText() + "/decision", a.token(), null,
                "{\"decision\":\"ACCEPT\"}", 200);
        call("PUT", "/friend-requests/" + fromD.path("id").asText() + "/decision", a.token(), null,
                "{\"decision\":\"ACCEPT\"}", 200);
        JsonNode friendsPage1 = call("GET", "/friends?limit=1", a.token(), null, null, 200);
        String friendCursor = friendsPage1.path("nextCursor").asText();
        assertThat(friendCursor).isNotBlank();
        JsonNode friendsPage2 = call("GET", "/friends?limit=1&cursor=" + friendCursor, a.token(), null, null, 200);
        assertThat(friendsPage2.at("/items/0/id").asText()).isNotEqualTo(friendsPage1.at("/items/0/id").asText());
    }

    @Test
    void differentKeysCannotDispatchSameOrbConcurrently() throws Exception {
        User a = signup("racea"); User b = signup("raceb");
        JsonNode request = call("POST", "/friend-requests", a.token(), null,
                "{\"friendCode\":\"" + b.login() + "\"}", 201);
        call("PUT", "/friend-requests/" + request.path("id").asText() + "/decision", b.token(), null,
                "{\"decision\":\"ACCEPT\"}", 200);
        String orbId = call("POST", "/orbs", a.token(), UUID.randomUUID().toString(),
                "{\"emotionIds\":[\"red_01\"]}", 201).at("/orb/id").asText();
        String path = "/orbs/" + orbId + "/dispatches";
        String body = "{\"route\":\"FRIEND\",\"friendId\":\"" + b.id() + "\"}";
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> first = () -> { start.await(); return status("POST", path, a.token(), UUID.randomUUID().toString(), body); };
            var one = pool.submit(first); var two = pool.submit(first); start.countDown();
            assertThat(java.util.List.of(one.get(), two.get())).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(count("SELECT COUNT(*) FROM dispatch WHERE orb_id=?", orbId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receipt WHERE dispatch_id=(SELECT id FROM dispatch WHERE orb_id=?)",
                Integer.class, UUID.fromString(orbId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM emotion_record WHERE dispatch_id=(SELECT id FROM dispatch WHERE orb_id=?)",
                Integer.class, UUID.fromString(orbId))).isEqualTo(2);
    }

    @Test
    void declinedRequestCanBeReplacedButConflictingDecisionIsRejected() throws Exception {
        User a = signup("declinea"); User b = signup("declineb");
        String body = "{\"friendCode\":\"" + b.login() + "\"}";
        String id = call("POST", "/friend-requests", a.token(), null, body, 201).path("id").asText();
        String path = "/friend-requests/" + id + "/decision";
        assertThat(call("PUT", path, b.token(), null, "{\"decision\":\"DECLINE\"}", 200)
                .path("status").asText()).isEqualTo("DECLINED");
        assertThat(call("PUT", path, b.token(), null, "{\"decision\":\"DECLINE\"}", 200)
                .path("status").asText()).isEqualTo("DECLINED");
        assertThat(call("PUT", path, b.token(), null, "{\"decision\":\"ACCEPT\"}", 409)
                .at("/error/code").asText()).isEqualTo("REQUEST_CLOSED");
        assertThat(call("POST", "/friend-requests", a.token(), null, body, 201).path("id").asText())
                .isNotEqualTo(id);
        assertThat(call("GET", "/friends", a.token(), null, null, 200).path("items").size()).isZero();
    }

    private User signup(String prefix) throws Exception {
        String login = prefix + "_" + UUID.randomUUID().toString().substring(0, 6);
        JsonNode signed = call("POST", "/auth/signup", null, null,
                "{\"loginId\":\"" + login + "\",\"password\":\"secret123\"}", 201);
        return new User(signed.at("/user/id").asText(), login, signed.at("/session/accessToken").asText());
    }

    private int count(String sql, String id) {
        return jdbc.queryForObject(sql, Integer.class, UUID.fromString(id));
    }

    private int status(String method, String path, String token, String key, String body) throws Exception {
        var builder = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), "/api/v1" + path);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(builder).andReturn().getResponse().getStatus();
    }

    private JsonNode call(String method, String path, String token, String key, String body, int expected) throws Exception {
        var builder = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), "/api/v1" + path);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body);
        var response = mvc.perform(builder).andReturn().getResponse();
        assertThat(response.getStatus()).as(method + " " + path + " body=" + response.getContentAsString()).isEqualTo(expected);
        return response.getContentAsString().isBlank() ? null : json.readTree(response.getContentAsString());
    }

    private record User(String id, String login, String token) {}
}
