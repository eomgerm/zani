package com.a105.zani.session.presentation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.a105.zani.auth.application.port.TokenProvider;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** presence 컨트롤러의 웹 레이어 검증. 실제 서명 JWT로 인증 필터를 통과시켜 인증 파싱과 예외별 HTTP 상태 매핑을 확인한다. 로컬 MySQL/Redis가 떠 있어야 통과한다. */
@SpringBootTest
class SessionPresenceControllerTest {

    private static final long NON_MEMBER_ID = -900L;
    private static final long MISSING_SESSION_ID = 9_000_900L;
    private static final String BODY = "{\"heartbeatAt\":\"2026-07-24T12:30:00Z\",\"connectionState\":\"CONNECTED\"}";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TokenProvider tokenProvider;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void respondsUnauthorizedWithoutAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/{sessionId}/presence", MISSING_SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void respondsForbiddenWhenTheUserIsNotASessionMember() throws Exception {
        String accessToken =
                tokenProvider.issueAccessToken(String.valueOf(NON_MEMBER_ID)).value();

        mockMvc.perform(post("/api/v1/sessions/{sessionId}/presence", MISSING_SESSION_ID)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void respondsBadRequestWhenTheRequestBodyIsIncomplete() throws Exception {
        String accessToken =
                tokenProvider.issueAccessToken(String.valueOf(NON_MEMBER_ID)).value();

        mockMvc.perform(post("/api/v1/sessions/{sessionId}/presence", MISSING_SESSION_ID)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
