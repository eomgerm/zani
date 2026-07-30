package com.a105.zani.auth.presentation.controller;

import java.time.Instant;
import java.util.UUID;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.a105.zani.auth.application.port.RefreshSession;
import com.a105.zani.auth.application.port.RefreshSessionPort;
import com.a105.zani.auth.application.port.TokenProvider;
import com.a105.zani.common.config.CorsProperties;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 재발급 엔드포인트의 웹 레이어 검증. 로컬 Redis 가 떠 있어야 통과한다.
 *
 * <p>핵심은 <b>실패했을 때 쿠키를 만료시키는지</b>다. 무효한 토큰을 브라우저가 계속 들고 있으면 새로고침마다 같은 401 이 나고 스스로 회복되지 않아, 사용자가 쿠키를 직접 지워야만 벗어난다.
 */
@SpringBootTest
class RefreshControllerTest {

    private static final String SUBJECT = "9700244";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private RefreshSessionPort refreshSessionPort;

    /** RefreshOriginFilter 가 Origin 헤더를 요구한다. 값을 박아 두면 CORS 설정이 바뀔 때 같이 깨진다. */
    @Autowired
    private CorsProperties corsProperties;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    private MockHttpServletRequestBuilder refreshRequest() {
        return post("/api/v1/auth/refresh")
                .header(HttpHeaders.ORIGIN, corsProperties.allowedOrigins().getFirst());
    }

    /** 브라우저가 이 쿠키를 지우려면 Path 까지 발급 때와 같아야 한다. */
    private static ResultMatcher clearedRefreshTokenCookie() {
        return header().string(
                        "Set-Cookie",
                        allOf(
                                containsString("refresh_token="),
                                containsString("Max-Age=0"),
                                containsString("Path=/api/v1/auth")));
    }

    private String validRefreshTokenWithSession() {
        String tokenId = UUID.randomUUID().toString();
        String refreshToken = tokenProvider.issueRefreshToken(SUBJECT, tokenId).value();
        refreshSessionPort.create(
                new RefreshSession(tokenId, SUBJECT, Instant.now().plusSeconds(3600)));
        return refreshToken;
    }

    @Test
    void clearsTheCookieWhenTheRefreshTokenIsUnreadable() throws Exception {
        mockMvc.perform(refreshRequest().cookie(new Cookie("refresh_token", "garbage")))
                .andExpect(status().isUnauthorized())
                .andExpect(clearedRefreshTokenCookie());
    }

    /** 쿠키가 아예 없어도 같은 경로를 지난다. 남아 있을지 모르는 다른 Path 의 잔여 쿠키까지 정리된다. */
    @Test
    void clearsTheCookieWhenThereIsNoRefreshTokenAtAll() throws Exception {
        mockMvc.perform(refreshRequest()).andExpect(status().isUnauthorized()).andExpect(clearedRefreshTokenCookie());
    }

    /** 회전은 1회용이다. 같은 토큰으로 두 번째 호출하면 세션이 이미 사라져 401 이고, 이때 브라우저가 그 토큰을 버려야 한다. */
    @Test
    void clearsTheCookieWhenTheSessionWasAlreadyRotated() throws Exception {
        String refreshToken = validRefreshTokenWithSession();

        mockMvc.perform(refreshRequest().cookie(new Cookie("refresh_token", refreshToken)))
                .andExpect(status().isOk());

        mockMvc.perform(refreshRequest().cookie(new Cookie("refresh_token", refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(clearedRefreshTokenCookie());
    }

    /** 정상 회전은 기존대로 새 쿠키를 내려준다. 만료 헤더가 섞이면 로그인 직후 세션이 사라진다. */
    @Test
    void issuesAReplacementCookieOnSuccess() throws Exception {
        String refreshToken = validRefreshTokenWithSession();

        mockMvc.perform(refreshRequest().cookie(new Cookie("refresh_token", refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(header().string(
                                "Set-Cookie",
                                allOf(
                                        containsString("refresh_token="),
                                        containsString("HttpOnly"),
                                        containsString("Path=/api/v1/auth"),
                                        not(containsString("Max-Age=0")))));
    }
}
