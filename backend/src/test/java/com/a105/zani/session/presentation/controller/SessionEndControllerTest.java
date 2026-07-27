package com.a105.zani.session.presentation.controller;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.a105.zani.auth.application.port.TokenProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 수업 종료 컨트롤러의 웹 레이어 검증. 실제 서명 JWT로 인증 필터 체인을 통과시켜 인증 주체 파싱과 예외별 HTTP 상태·응답 형식을 확인한다. 로컬 MySQL/Redis가 떠 있어야 통과하며, 테스트
 * 트랜잭션은 종료 시 롤백되어 데이터를 남기지 않는다.
 */
@SpringBootTest
class SessionEndControllerTest {

    private static final long INSTRUCTOR_ID = 9_500_001L;
    private static final long STUDENT_ID = 9_500_002L;
    private static final long SESSION_ID = 9_500_100L;
    private static final long MISSING_SESSION_ID = 9_500_900L;
    private static final Instant STARTED_AT = Instant.now();

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    /** Hibernate가 UTC로 저장하므로 직접 INSERT 할 때도 UTC 기준 시각을 넣는다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String tokenOf(long userId) {
        return tokenProvider.issueAccessToken(String.valueOf(userId)).value();
    }

    /** 강사 1명과 학생 1명이 참여한 LIVE 세션을 만든다. */
    private void insertLiveSession() {
        jdbcTemplate.update(
                "INSERT IGNORE INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?), (?, ?, ?, ?, ?, ?)",
                INSTRUCTOR_ID,
                "google-" + INSTRUCTOR_ID,
                INSTRUCTOR_ID + "@example.com",
                "종료 테스트 강사",
                utc(STARTED_AT),
                utc(STARTED_AT),
                STUDENT_ID,
                "google-" + STUDENT_ID,
                STUDENT_ID + "@example.com",
                "종료 테스트 학생",
                utc(STARTED_AT),
                utc(STARTED_AT));
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'LIVE', 'NOT_STARTED', ?, ?, ?)",
                SESSION_ID,
                INSTRUCTOR_ID,
                "종료 테스트",
                "ENDTEST1",
                utc(STARTED_AT),
                utc(STARTED_AT),
                utc(STARTED_AT));
    }

    private String statusOf(long sessionId) {
        return jdbcTemplate.queryForObject("SELECT status FROM sessions WHERE id = ?", String.class, sessionId);
    }

    @Test
    void respondsUnauthorizedWithoutAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/{sessionId}/end", MISSING_SESSION_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void respondsNotFoundForAnUnknownSession() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/{sessionId}/end", MISSING_SESSION_ID)
                        .header("Authorization", "Bearer " + tokenOf(INSTRUCTOR_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_APP_005"));
    }

    @Test
    @Transactional
    void respondsForbiddenWhenTheRequesterIsNotTheInstructor() throws Exception {
        insertLiveSession();

        mockMvc.perform(post("/api/v1/sessions/{sessionId}/end", SESSION_ID)
                        .header("Authorization", "Bearer " + tokenOf(STUDENT_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SESSION_APP_006"));

        // 권한 없는 요청은 세션 상태를 바꾸지 않아야 한다.
        assertEquals("LIVE", statusOf(SESSION_ID));
    }

    @Test
    @Transactional
    void endsTheSessionForTheInstructorWhoOpenedIt() throws Exception {
        insertLiveSession();

        mockMvc.perform(post("/api/v1/sessions/{sessionId}/end", SESSION_ID)
                        .header("Authorization", "Bearer " + tokenOf(INSTRUCTOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.data.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.data.status").value("ENDED"))
                .andExpect(jsonPath("$.data.ended").value(true));

        assertEquals("ENDED", statusOf(SESSION_ID));
    }

    @Test
    @Transactional
    void reportsNoTransitionWhenTheSessionHasAlreadyEnded() throws Exception {
        insertLiveSession();
        String accessToken = tokenOf(INSTRUCTOR_ID);
        mockMvc.perform(post("/api/v1/sessions/{sessionId}/end", SESSION_ID)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        // 버튼 연타·재시도에 안전해야 한다. 두 번째 호출은 오류가 아니라 상태만 돌려준다.
        mockMvc.perform(post("/api/v1/sessions/{sessionId}/end", SESSION_ID)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ENDED"))
                .andExpect(jsonPath("$.data.ended").value(false));
    }
}
