package com.ganzi.emori;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties="spring.datasource.url=${EMORI_STAGE4_MIDNIGHT_DB_URL:jdbc:h2:mem:stage4_midnight;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}")
@AutoConfigureMockMvc
class Stage4MidnightTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean Clock clock;

    @Test void kstMidnightUsesTwoSeparateUsageRows() throws Exception {
        jdbc.update("UPDATE user_settings SET anonymous_enabled=FALSE");
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        User a=signup(),b=signup();
        for(User u:new User[]{a,b}) {
            jdbc.update("UPDATE app_user SET adult_status='VERIFIED_19_PLUS' WHERE id=?",UUID.fromString(u.id()));
            var response=mvc.perform(patch("/api/v1/me/settings").header("Authorization","Bearer "+u.token())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"anonymousEnabled\":true}"))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
        }
        when(clock.instant()).thenReturn(Instant.parse("2026-11-15T14:59:59Z"));
        send(a);
        when(clock.instant()).thenReturn(Instant.parse("2026-11-15T15:00:01Z"));
        send(a);
        assertThat(jdbc.queryForObject("SELECT sent_count FROM anon_daily_usage WHERE user_id=? AND usage_date=DATE '2026-11-15'",
                Integer.class,UUID.fromString(a.id()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sent_count FROM anon_daily_usage WHERE user_id=? AND usage_date=DATE '2026-11-16'",
                Integer.class,UUID.fromString(a.id()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT received_count FROM anon_daily_usage WHERE user_id=? AND usage_date=DATE '2026-11-15'",
                Integer.class,UUID.fromString(b.id()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT received_count FROM anon_daily_usage WHERE user_id=? AND usage_date=DATE '2026-11-16'",
                Integer.class,UUID.fromString(b.id()))).isEqualTo(1);
    }
    private User signup() throws Exception {
        String login="mid"+UUID.randomUUID().toString().substring(0,8);
        var response=mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\""+login+"\",\"password\":\"secret123\"}"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        JsonNode body=json.readTree(response.getContentAsString());
        return new User(body.at("/user/id").asText(),body.at("/session/accessToken").asText());
    }
    private void send(User a) throws Exception {
        var draft=mvc.perform(post("/api/v1/orbs").header("Authorization","Bearer "+a.token())
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"emotionIds\":[\"yellow_01\"]}"))
                .andReturn().getResponse();
        assertThat(draft.getStatus()).as(draft.getContentAsString()).isEqualTo(201);
        String orb=json.readTree(draft.getContentAsString()).at("/orb/id").asText();
        var sent=mvc.perform(post("/api/v1/orbs/{id}/dispatches",orb).header("Authorization","Bearer "+a.token())
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"route\":\"ANONYMOUS\"}"))
                .andReturn().getResponse();
        assertThat(sent.getStatus()).as(sent.getContentAsString()).isEqualTo(201);
    }
    private record User(String id,String token){}
}
