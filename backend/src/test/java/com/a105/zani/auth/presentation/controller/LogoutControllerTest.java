package com.a105.zani.auth.presentation.controller;

import java.time.Instant;
import java.util.UUID;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.a105.zani.auth.application.port.RefreshSession;
import com.a105.zani.auth.application.port.RefreshSessionPort;
import com.a105.zani.auth.application.port.TokenProvider;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그아웃 엔드포인트의 웹 레이어 검증. 인증 없이 호출할 수 있는지(permitAll), refresh 세션이 실제로 무효화되는지, 응답에서 refresh_token 쿠키가 삭제되는지를 확인한다. 로컬
 * Redis가 떠 있어야 통과한다.
 */
@SpringBootTest
class LogoutControllerTest {

    private static final String SUBJECT = "9700001";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private RefreshSessionPort refreshSessionPort;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void succeedsWithoutARefreshTokenCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(clearedRefreshTokenCookie());
    }

    @Test
    void succeedsWithAnAlreadyInvalidRefreshTokenCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("refresh_token", "garbage")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(clearedRefreshTokenCookie());
    }

    @Test
    void revokesTheSessionForAValidRefreshToken() throws Exception {
        String tokenId = UUID.randomUUID().toString();
        String refreshToken = tokenProvider.issueRefreshToken(SUBJECT, tokenId).value();
        refreshSessionPort.create(
                new RefreshSession(tokenId, SUBJECT, Instant.now().plusSeconds(3600)));

        mockMvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("refresh_token", refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(clearedRefreshTokenCookie());

        // 무효화된 세션은 더 이상 회전(재발급)될 수 없어야 한다.
        RefreshSession replacement = new RefreshSession(
                UUID.randomUUID().toString(), SUBJECT, Instant.now().plusSeconds(3600));
        assertFalse(refreshSessionPort.rotate(tokenId, SUBJECT, replacement));
    }

    /** 응답이 refresh_token 쿠키를 즉시 만료(Max-Age=0)로 덮어써 브라우저에서 지우는지 확인한다. 브라우저는 Path까지 일치해야 지우므로 발급 때와 같은 Path인지도 본다. */
    private static org.springframework.test.web.servlet.ResultMatcher clearedRefreshTokenCookie() {
        return header().string(
                        "Set-Cookie",
                        allOf(
                                containsString("refresh_token="),
                                containsString("Max-Age=0"),
                                containsString("Path=/api/v1/auth")));
    }
}
