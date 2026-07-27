package com.a105.zani.audioclip.presentation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.a105.zani.auth.application.port.TokenProvider;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 클립 요청 SSE 스트림의 웹 레이어 검증. 자격 없는 구독이 스트림을 열지 못하는지 확인한다. 로컬 MySQL/Redis가 떠 있어야 통과한다. */
@SpringBootTest
class AudioClipStreamControllerTest {

    private static final long NON_MEMBER_ID = -900L;
    private static final long MISSING_SESSION_ID = 9_000_900L;

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
    void 인증이_없으면_401() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/audio-clip-requests/stream", MISSING_SESSION_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 세션_강사가_아니면_403() throws Exception {
        String accessToken =
                tokenProvider.issueAccessToken(String.valueOf(NON_MEMBER_ID)).value();

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/audio-clip-requests/stream", MISSING_SESSION_ID)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden());
    }
}
