package com.a105.zani.recording.presentation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * webhook 컨트롤러의 웹 레이어 검증: 경로는 사용자 JWT 없이 접근 가능하되(permitAll), LiveKit 서명이 없거나 틀리면 401이어야 한다. 로컬 MySQL/Redis가 떠 있어야 통과한다.
 */
@SpringBootTest
class LiveKitWebhookControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void 서명_헤더가_없으면_401() throws Exception {
        mockMvc.perform(post("/api/v1/internal/recordings/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"egress_ended\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 서명이_틀리면_401() throws Exception {
        // 에러 코드까지 확인해 resource-server 필터가 아니라 webhook 서명 검증이 401을 낸 것임을 보장한다.
        mockMvc.perform(post("/api/v1/internal/recordings/webhook")
                        .header("Authorization", "invalid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"egress_ended\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("RECORDING_WEBHOOK_001"));
    }

    @Test
    void Bearer_접두어가_붙은_잘못된_서명도_webhook_검증이_401을_낸다() throws Exception {
        // Bearer 접두어가 있어도 resource-server 필터가 가로채지 않고(BearerTokenResolver 예외 경로) 서명 검증까지 도달해야 한다.
        mockMvc.perform(post("/api/v1/internal/recordings/webhook")
                        .header("Authorization", "Bearer invalid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"egress_ended\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("RECORDING_WEBHOOK_001"));
    }
}
