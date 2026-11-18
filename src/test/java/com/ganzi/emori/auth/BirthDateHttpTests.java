package com.ganzi.emori.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:birth_date_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class BirthDateHttpTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test void selfReportControlsAnonymousAndPreservesVerifiedStatus() throws Exception {
        String login = "age_" + UUID.randomUUID().toString().substring(0, 8);
        var signup = mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + login + "\",\"password\":\"secret123\"}"))
                .andReturn().getResponse();
        assertThat(signup.getStatus()).isEqualTo(201);
        var created = json.readTree(signup.getContentAsString());
        String bearer = "Bearer " + created.at("/session/accessToken").asText();
        assertThat(created.at("/user/adultStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(patchAnonymous(bearer)).isEqualTo(403);
        assertThat(putBirth(bearer, "2008-02-30").getStatus()).isEqualTo(422);
        assertThat(putBirth(bearer, "2099-01-01").getStatus()).isEqualTo(422);

        String minorDate = LocalDate.now().minusYears(18).toString();
        var minor = putBirth(bearer, minorDate);
        assertThat(minor.getStatus()).isEqualTo(200);
        assertThat(json.readTree(minor.getContentAsString()).path("adultStatus").asText())
                .isEqualTo("SELF_REPORTED_UNDER_19");
        assertThat(patchAnonymous(bearer)).isEqualTo(403);

        String adultDate = LocalDate.now().minusYears(20).toString();
        var adult = putBirth(bearer, adultDate);
        assertThat(adult.getStatus()).isEqualTo(200);
        assertThat(json.readTree(adult.getContentAsString()).path("adultStatus").asText())
                .isEqualTo("SELF_REPORTED_19_PLUS");
        assertThat(patchAnonymous(bearer)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT birth_date FROM app_user WHERE login_id=?", LocalDate.class, login))
                .isEqualTo(LocalDate.parse(adultDate));

        jdbc.update("UPDATE app_user SET adult_status='VERIFIED_19_PLUS' WHERE login_id=?", login);
        assertThat(json.readTree(putBirth(bearer, adultDate).getContentAsString())
                .path("adultStatus").asText()).isEqualTo("VERIFIED_19_PLUS");
    }

    @Test void leapDayUsesMarchFirstAndBirthdayBoundary() {
        assertThat(AuthService.isAdult(LocalDate.of(2008, 2, 29), LocalDate.of(2027, 2, 28))).isFalse();
        assertThat(AuthService.isAdult(LocalDate.of(2008, 2, 29), LocalDate.of(2027, 3, 1))).isTrue();
        assertThat(AuthService.isAdult(LocalDate.of(2007, 11, 18), LocalDate.of(2026, 11, 17))).isFalse();
        assertThat(AuthService.isAdult(LocalDate.of(2007, 11, 18), LocalDate.of(2026, 11, 18))).isTrue();
    }

    private org.springframework.mock.web.MockHttpServletResponse putBirth(String bearer, String date) throws Exception {
        return mvc.perform(put("/api/v1/me/birth-date").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"birthDate\":\"" + date + "\"}"))
                .andReturn().getResponse();
    }

    private int patchAnonymous(String bearer) throws Exception {
        return mvc.perform(patch("/api/v1/me/settings").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"anonymousEnabled\":true}"))
                .andReturn().getResponse().getStatus();
    }
}
