package com.ganzi.emori;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

@SpringBootTest
@AutoConfigureMockMvc
class CorsHttpTests {
    private static final String FRONTEND = "https://emori.expo.app";

    @Autowired MockMvc mvc;

    @Test
    void allowsBrowserPreflightAndActualRequestFromDemoOrigin() throws Exception {
        var preflight = mvc.perform(options("/api/v1/orbs")
                .header("Origin", FRONTEND)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
                .andReturn().getResponse();
        assertThat(preflight.getStatus()).isEqualTo(200);
        assertThat(preflight.getHeader("Access-Control-Allow-Origin")).isEqualTo(FRONTEND);
        assertThat(preflight.getHeader("Access-Control-Allow-Headers"))
                .containsIgnoringCase("authorization")
                .containsIgnoringCase("content-type")
                .containsIgnoringCase("idempotency-key");

        var actual = mvc.perform(get("/api/v1/auth/login-id-availability")
                .param("loginId", "available_demo_user").header("Origin", FRONTEND))
                .andReturn().getResponse();
        assertThat(actual.getStatus()).isEqualTo(200);
        assertThat(actual.getHeader("Access-Control-Allow-Origin")).isEqualTo(FRONTEND);
    }

    @Test
    void rejectsOtherBrowserOrigins() throws Exception {
        var response = mvc.perform(options("/api/v1/orbs")
                .header("Origin", "https://other.example")
                .header("Access-Control-Request-Method", "POST"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }
}
