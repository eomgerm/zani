package com.a105.zani.attention.presentation.controller;

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

/** 판정 이벤트 컨트롤러의 웹 레이어 검증. 실제 서명 JWT로 인증 필터를 통과시켜 인증 파싱과 예외별 HTTP 상태 매핑을 확인한다. 로컬 MySQL/Redis가 떠 있어야 통과한다. */
@SpringBootTest
class AttentionEventControllerTest {

    private static final long NON_MEMBER_ID = -910L;
    private static final long MISSING_SESSION_ID = 9_000_910L;
    private static final String BODY = """
            {"outcome":"BARELY_ENGAGED","lowEngagement":true,"windowStartedAt":"2026-07-28T09:00:00Z","observedAt":"2026-07-28T09:00:10Z",
            "signalQuality":0.92,"featureSchemaVersion":"mediapipe_98_v1","engineVersion":"e0g-1","clientEventId":"web-test-1"}""";

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
        mockMvc.perform(post("/api/v1/sessions/{sessionId}/attention-events", MISSING_SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void respondsForbiddenWhenTheUserIsNotASessionMember() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/{sessionId}/attention-events", MISSING_SESSION_ID)
                        .header("Authorization", "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsARequestCarryingRawSignalsThatTheContractForbids() throws Exception {
        String withLandmarks = """
                {"outcome":"BARELY_ENGAGED","lowEngagement":true,"windowStartedAt":"2026-07-28T09:00:00Z","observedAt":"2026-07-28T09:00:10Z","signalQuality":0.92,"featureSchemaVersion":"mediapipe_98_v1","engineVersion":"e0g-1","clientEventId":"web-test-2","landmarks":[[0.1,0.2,0.3]]}""";

        mockMvc.perform(post("/api/v1/sessions/{sessionId}/attention-events", MISSING_SESSION_ID)
                        .header("Authorization", "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withLandmarks))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnUnknownAttentionState() throws Exception {
        String unknownState = """
                {"outcome":"NEEDS_CHECK","lowEngagement":true,"windowStartedAt":"2026-07-28T09:00:00Z","observedAt":"2026-07-28T09:00:10Z","signalQuality":0.92,"featureSchemaVersion":"mediapipe_98_v1","engineVersion":"e0g-1","clientEventId":"web-test-3"}""";

        mockMvc.perform(post("/api/v1/sessions/{sessionId}/attention-events", MISSING_SESSION_ID)
                        .header("Authorization", "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unknownState))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsASignalQualityOutsideTheZeroToOneRange() throws Exception {
        String outOfRange = """
                {"outcome":"BARELY_ENGAGED","lowEngagement":true,"windowStartedAt":"2026-07-28T09:00:00Z","observedAt":"2026-07-28T09:00:10Z","signalQuality":1.5,"featureSchemaVersion":"mediapipe_98_v1","engineVersion":"e0g-1","clientEventId":"web-test-4"}""";

        mockMvc.perform(post("/api/v1/sessions/{sessionId}/attention-events", MISSING_SESSION_ID)
                        .header("Authorization", "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(outOfRange))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAMissingClientEventIdBecauseIdempotencyDependsOnIt() throws Exception {
        String withoutEventId = """
                {"outcome":"BARELY_ENGAGED","lowEngagement":true,"windowStartedAt":"2026-07-28T09:00:00Z","observedAt":"2026-07-28T09:00:10Z","signalQuality":0.92,"featureSchemaVersion":"mediapipe_98_v1","engineVersion":"e0g-1"}""";

        mockMvc.perform(post("/api/v1/sessions/{sessionId}/attention-events", MISSING_SESSION_ID)
                        .header("Authorization", "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withoutEventId))
                .andExpect(status().isBadRequest());
    }

    private String accessToken() {
        return tokenProvider.issueAccessToken(String.valueOf(NON_MEMBER_ID)).value();
    }
}
