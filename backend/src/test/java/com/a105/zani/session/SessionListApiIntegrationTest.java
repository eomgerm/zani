package com.a105.zani.session;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.a105.zani.auth.application.port.TokenProvider;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 내 수업 목록의 응답 계약 검증. 로컬 MySQL 이 떠 있어야 통과한다.
 *
 * <p><b>단위 테스트로는 이 파일이 보는 것을 볼 수 없다.</b> {@code GetSessionListServiceTest} 는 포트를 스텁으로 바꾸므로, 정작 어려운 부분인 <b>주최·참가 두 목록을
 * 합치고 참가자 수와 리포트 상태를 실제 테이블에서 끌어오는 조립</b>이 빠진다.
 *
 * <p>같은 수업이 주최 목록과 참가 목록에 겹쳐 잡히는 것(강사는 자기 수업의 참가자이기도 하다)도 실제 행이 있어야 재현된다.
 */
@SpringBootTest
class SessionListApiIntegrationTest {

    private static final long INSTRUCTOR_ID = 9_500_910L;
    private static final long STUDENT_ID = 9_500_911L;
    private static final long STRANGER_ID = 9_500_912L;

    private static final long LIVE_SESSION_ID = 9_500_920L;
    private static final long ENDED_SESSION_ID = 9_500_921L;

    private static final long LIVE_INSTRUCTOR_PARTICIPANT_ID = 9_500_930L;
    private static final long LIVE_STUDENT_PARTICIPANT_ID = 9_500_931L;
    private static final long ENDED_INSTRUCTOR_PARTICIPANT_ID = 9_500_932L;
    private static final long ENDED_STUDENT_PARTICIPANT_ID = 9_500_933L;

    private static final long PIPELINE_JOB_ID = 9_500_940L;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private Instant now;

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        cleanUp();

        insertMember(INSTRUCTOR_ID, "박강사");
        insertMember(STUDENT_ID, "김민수");
        insertMember(STRANGER_ID, "아무 수업도 없는 사람");

        insertSession(LIVE_SESSION_ID, "진행 중 수업", "LIV00251", "LIVE", null);
        insertSession(ENDED_SESSION_ID, "끝난 수업", "END00251", "ENDED", now.minusSeconds(60));

        // 강사는 생성과 동시에 참가자로 등록된다. 그래서 주최 목록과 참가 목록에 같은 세션이 겹쳐 잡힌다.
        insertParticipant(LIVE_INSTRUCTOR_PARTICIPANT_ID, LIVE_SESSION_ID, INSTRUCTOR_ID, "INSTRUCTOR");
        insertParticipant(LIVE_STUDENT_PARTICIPANT_ID, LIVE_SESSION_ID, STUDENT_ID, "STUDENT");
        insertParticipant(ENDED_INSTRUCTOR_PARTICIPANT_ID, ENDED_SESSION_ID, INSTRUCTOR_ID, "INSTRUCTOR");
        insertParticipant(ENDED_STUDENT_PARTICIPANT_ID, ENDED_SESSION_ID, STUDENT_ID, "STUDENT");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM pipeline_jobs WHERE id = ?", PIPELINE_JOB_ID);
        jdbcTemplate.update(
                "DELETE FROM session_participants WHERE session_id IN (?, ?)", LIVE_SESSION_ID, ENDED_SESSION_ID);
        jdbcTemplate.update("DELETE FROM sessions WHERE id IN (?, ?)", LIVE_SESSION_ID, ENDED_SESSION_ID);
    }

    @Test
    void 강사가_연_수업은_강사_역할로_한_번만_나온다() throws Exception {
        list(INSTRUCTOR_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.sessionId == '%s')].role".formatted(LIVE_SESSION_ID))
                        .value("INSTRUCTOR"))
                // 주최 목록과 참가 목록에 겹쳐 잡히므로 걸러내지 않으면 같은 수업이 두 번 뜬다.
                .andExpect(jsonPath("$.data[?(@.sessionId == '%s')]".formatted(LIVE_SESSION_ID))
                        .isNotEmpty());
    }

    @Test
    void 학생으로_참여한_수업은_학생_역할로_나온다() throws Exception {
        list(STUDENT_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.sessionId == '%s')].role".formatted(LIVE_SESSION_ID))
                        .value("STUDENT"));
    }

    @Test
    void 제목과_시각과_참가자_수를_함께_내린다() throws Exception {
        list(STUDENT_ID)
                .andExpect(jsonPath("$.data[?(@.sessionId == '%s')].title".formatted(LIVE_SESSION_ID))
                        .value("진행 중 수업"))
                // 학생 카드는 "누구 수업인지" 를 보여준다. 주최 강사 이름이 함께 와야 한다.
                .andExpect(jsonPath("$.data[?(@.sessionId == '%s')].instructorName".formatted(LIVE_SESSION_ID))
                        .value("박강사"))
                .andExpect(jsonPath("$.data[?(@.sessionId == '%s')].participantCount".formatted(LIVE_SESSION_ID))
                        .value(2))
                .andExpect(jsonPath("$.data[?(@.sessionId == '%s')].startedAt".formatted(LIVE_SESSION_ID))
                        .isNotEmpty())
                // 진행 중인 수업에는 종료 시각이 없다.
                .andExpect(jsonPath("$.data[?(@.sessionId == '%s')].endedAt".formatted(LIVE_SESSION_ID))
                        .value((Object) null))
                .andExpect(jsonPath("$.data[?(@.sessionId == '%s')].endedAt".formatted(ENDED_SESSION_ID))
                        .isNotEmpty());
    }

    /** 나갔다가 돌아오는 경로다. 참가자 행은 퇴장해도 남으므로 진행 중이면 계속 재입장할 수 있어야 한다. */
    @Test
    void 진행_중이고_참가자_행이_있으면_재입장_가능이다() throws Exception {
        list(STUDENT_ID)
                .andExpect(jsonPath("$.data[?(@.sessionId == '%s')].rejoinable".formatted(LIVE_SESSION_ID))
                        .value(true))
                .andExpect(jsonPath("$.data[?(@.sessionId == '%s')].rejoinable".formatted(ENDED_SESSION_ID))
                        .value(false));
    }

    @Test
    void 사후_처리_작업이_없으면_리포트_상태는_NONE_이다() throws Exception {
        list(STUDENT_ID)
                .andExpect(jsonPath("$.data[?(@.sessionId == '%s')].reportStatus".formatted(ENDED_SESSION_ID))
                        .value("NONE"));
    }

    /** 파이프라인 단계를 그대로 내리면 화면이 구현에 묶인다. 중간 단계는 한 값으로 접혀야 한다. */
    @Test
    void 처리_중인_단계는_PROCESSING_으로_접힌다() throws Exception {
        insertPipelineJob("ANALYZING");

        list(STUDENT_ID)
                .andExpect(jsonPath("$.data[?(@.sessionId == '%s')].reportStatus".formatted(ENDED_SESSION_ID))
                        .value("PROCESSING"));
    }

    @Test
    void 발행되면_COMPLETED_이고_실패는_그대로_FAILED_이다() throws Exception {
        insertPipelineJob("PUBLISHED");
        list(STUDENT_ID)
                .andExpect(jsonPath("$.data[?(@.sessionId == '%s')].reportStatus".formatted(ENDED_SESSION_ID))
                        .value("COMPLETED"));

        jdbcTemplate.update("UPDATE pipeline_jobs SET status = 'FAILED' WHERE id = ?", PIPELINE_JOB_ID);
        list(STUDENT_ID)
                .andExpect(jsonPath("$.data[?(@.sessionId == '%s')].reportStatus".formatted(ENDED_SESSION_ID))
                        .value("FAILED"));
    }

    @Test
    void 참여한_수업이_없으면_빈_목록이다() throws Exception {
        list(STRANGER_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void 인증하지_않으면_401() throws Exception {
        mockMvc.perform(get("/api/v1/sessions")).andExpect(status().isUnauthorized());
    }

    private ResultActions list(long memberId) throws Exception {
        return mockMvc.perform(get("/api/v1/sessions")
                .header(
                        "Authorization",
                        "Bearer "
                                + tokenProvider
                                        .issueAccessToken(String.valueOf(memberId))
                                        .value()));
    }

    private void insertPipelineJob(String status) {
        jdbcTemplate.update(
                "INSERT INTO pipeline_jobs (id, session_id, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                PIPELINE_JOB_ID,
                ENDED_SESSION_ID,
                status,
                utc(now),
                utc(now));
    }

    private void insertMember(long id, String displayName) {
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE display_name = VALUES(display_name)",
                id,
                "google-" + id,
                id + "@example.com",
                displayName,
                utc(now),
                utc(now));
    }

    private void insertSession(long id, String title, String inviteCode, String status, Instant endedAt) {
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, ended_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'NOT_STARTED', ?, ?, ?, ?)",
                id,
                INSTRUCTOR_ID,
                title,
                inviteCode,
                status,
                utc(now.minusSeconds(600)),
                endedAt == null ? null : utc(endedAt),
                utc(now),
                utc(now));
    }

    private void insertParticipant(long id, long sessionId, long memberId, String role) {
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at,"
                        + " last_accessed_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                sessionId,
                memberId,
                role,
                utc(now.minusSeconds(300)),
                utc(now),
                utc(now),
                utc(now));
    }
}
