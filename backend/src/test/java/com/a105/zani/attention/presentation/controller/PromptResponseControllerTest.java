package com.a105.zani.attention.presentation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.a105.zani.auth.application.port.TokenProvider;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 프롬프트 응답 컨트롤러의 웹 레이어 검증. 실제 서명 JWT로 인증 필터를 통과시켜 요청 계약과 예외별 HTTP 상태 매핑을 확인한다. 로컬 MySQL/Redis가 떠 있어야 통과한다. */
@SpringBootTest
class PromptResponseControllerTest {

    private static final long NON_MEMBER_ID = -930L;
    private static final long MISSING_SESSION_ID = 9_000_930L;
    private static final String PROMPT_ID = "web-test-prompt";
    private static final String BODY = """
            {"kind":"UNDERSTANDING_CHECK","answer":"CONFUSED","shownAt":"2026-07-28T09:00:00Z",\
            "respondedAt":"2026-07-28T09:00:08Z"}""";

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
        mockMvc.perform(post("/api/v1/sessions/{sessionId}/prompts/{promptId}/responses", MISSING_SESSION_ID, PROMPT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void respondsForbiddenWhenTheUserIsNotASessionMember() throws Exception {
        send(BODY).andExpect(status().isForbidden());
    }

    @Test
    void rejectsARequestCarryingFieldsThatTheContractDoesNotDefine() throws Exception {
        send("""
                        {"kind":"UNDERSTANDING_CHECK","answer":"CONFUSED","shownAt":"2026-07-28T09:00:00Z",\
                        "respondedAt":"2026-07-28T09:00:08Z","landmarks":[[0.1,0.2]]}""").andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnUnknownPromptKind() throws Exception {
        send("""
                        {"kind":"MOOD_CHECK","answer":"CONFUSED","shownAt":"2026-07-28T09:00:00Z",\
                        "respondedAt":"2026-07-28T09:00:08Z"}""").andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnUnknownAnswer() throws Exception {
        send("""
                        {"kind":"UNDERSTANDING_CHECK","answer":"MAYBE","shownAt":"2026-07-28T09:00:00Z",\
                        "respondedAt":"2026-07-28T09:00:08Z"}""").andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAMissingShownAtBecauseDuplicateDetectionDependsOnIt() throws Exception {
        send("""
                        {"kind":"UNDERSTANDING_CHECK","answer":"CONFUSED",\
                        "respondedAt":"2026-07-28T09:00:08Z"}""").andExpect(status().isBadRequest());
    }

    private ResultActions send(String body) throws Exception {
        String accessToken =
                tokenProvider.issueAccessToken(String.valueOf(NON_MEMBER_ID)).value();
        return mockMvc.perform(
                post("/api/v1/sessions/{sessionId}/prompts/{promptId}/responses", MISSING_SESSION_ID, PROMPT_ID)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }
}
