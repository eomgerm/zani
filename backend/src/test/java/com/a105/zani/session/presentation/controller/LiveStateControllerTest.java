package com.a105.zani.session.presentation.controller;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.a105.zani.auth.application.port.TokenProvider;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실시간 상태 스냅샷 컨트롤러의 웹 레이어 검증. 실제 서명 JWT 로 인증 필터를 통과시켜 인증 파싱과 예외별 HTTP 상태 매핑을 확인한다. 로컬 MySQL/Redis 가 떠 있어야 통과한다.
 *
 * <p>스냅샷 내용 자체는 {@code GetLiveStateServiceTest} 가 다룬다. 여기서는 경계(인증·멤버십·종료)와 응답 봉투만 본다.
 */
@SpringBootTest
class LiveStateControllerTest {

    private static final long NON_MEMBER_ID = -900L;
    private static final long MEMBER_ID = 9_400_910L;
    private static final long SESSION_ID = 9_400_911L;
    private static final long PARTICIPANT_ID = 9_400_912L;
    private static final long ENDED_SESSION_ID = 9_400_913L;
    private static final long ENDED_PARTICIPANT_ID = 9_400_914L;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private Instant now;

    /** Hibernate 가 UTC 로 저장(jdbc.time_zone=UTC)하므로 직접 INSERT 할 때도 UTC 기준 시각을 넣는다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String accessTokenOf(long memberId) {
        return tokenProvider.issueAccessToken(String.valueOf(memberId)).value();
    }

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        insertMember();
        insertSession(SESSION_ID, "LIVE", "LIVE9411");
        insertSession(ENDED_SESSION_ID, "ENDED", "ENDS9413");
        insertParticipant(PARTICIPANT_ID, SESSION_ID);
        insertParticipant(ENDED_PARTICIPANT_ID, ENDED_SESSION_ID);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update(
                "DELETE FROM session_participants WHERE id IN (?, ?)", PARTICIPANT_ID, ENDED_PARTICIPANT_ID);
        jdbcTemplate.update("DELETE FROM sessions WHERE id IN (?, ?)", SESSION_ID, ENDED_SESSION_ID);
    }

    @Test
    void respondsUnauthorizedWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/live-state", SESSION_ID))
                .andExpect(status().isUnauthorized());
    }

    /** 비멤버에게 남의 수업 채팅 이력을 보여주면 안 된다. */
    @Test
    void respondsForbiddenWhenTheUserIsNotASessionMember() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/live-state", SESSION_ID)
                        .header("Authorization", "Bearer " + accessTokenOf(NON_MEMBER_ID)))
                .andExpect(status().isForbidden());
    }

    /** 멤버십을 먼저 확인하므로 없는 세션도 비멤버로 걸러진다(세션 존재 여부를 알리지 않는다). */
    @Test
    void respondsForbiddenForAnUnknownSession() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/live-state", 9_400_999L)
                        .header("Authorization", "Bearer " + accessTokenOf(MEMBER_ID)))
                .andExpect(status().isForbidden());
    }

    @Test
    void respondsConflictWhenTheSessionHasAlreadyEnded() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/live-state", ENDED_SESSION_ID)
                        .header("Authorization", "Bearer " + accessTokenOf(MEMBER_ID)))
                .andExpect(status().isConflict());
    }

    /** 손들기(64)가 채울 자리를 규격으로 먼저 둔다. 키가 사라지면 붙어 있는 클라이언트를 다 고쳐야 한다. */
    @Test
    void respondsWithTheSnapshotEnvelopeForAMember() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/live-state", SESSION_ID)
                        .header("Authorization", "Bearer " + accessTokenOf(MEMBER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.data.participants").isArray())
                .andExpect(jsonPath("$.data.chatMessages").isArray())
                .andExpect(jsonPath("$.data.raisedHandIdentities").isArray())
                .andExpect(jsonPath("$.data.participants[0].identity").value("p-" + PARTICIPANT_ID));
    }

    /** 봉투에 이메일 등 개인 식별 정보가 실리면 안 된다(티켓 63 요구사항). */
    @Test
    void doesNotExposePersonallyIdentifyingFields() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/live-state", SESSION_ID)
                        .header("Authorization", "Bearer " + accessTokenOf(MEMBER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.participants[0].email").doesNotExist())
                .andExpect(jsonPath("$.data.participants[0].memberId").doesNotExist());
    }

    private void insertMember() {
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE display_name = VALUES(display_name)",
                MEMBER_ID,
                "google-" + MEMBER_ID,
                MEMBER_ID + "@example.com",
                "스냅샷 테스트 사용자",
                utc(now),
                utc(now));
    }

    private void insertSession(long sessionId, String status, String inviteCode) {
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", sessionId);
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, ended_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'NOT_STARTED', ?, ?, ?, ?)",
                sessionId,
                MEMBER_ID,
                "스냅샷 테스트",
                inviteCode,
                status,
                utc(now.minusSeconds(600)),
                "ENDED".equals(status) ? utc(now) : null,
                utc(now),
                utc(now));
    }

    private void insertParticipant(long participantId, long sessionId) {
        jdbcTemplate.update("DELETE FROM session_participants WHERE id = ?", participantId);
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at,"
                        + " last_accessed_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'INSTRUCTOR', ?, ?, ?, ?)",
                participantId,
                sessionId,
                MEMBER_ID,
                utc(now.minusSeconds(600)),
                utc(now),
                utc(now),
                utc(now));
    }
}
