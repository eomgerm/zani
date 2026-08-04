package com.a105.zani.report;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.a105.zani.auth.application.port.TokenProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class StudentReportApiTest {

    private static final long INSTRUCTOR_ID = 9_112_500L;
    private static final long STUDENT_ID = 9_112_501L;
    private static final long OTHER_STUDENT_ID = 9_112_502L;
    private static final long OUTSIDER_ID = 9_112_503L;
    private static final long SESSION_ID = 9_112_510L;
    private static final long LIVE_SESSION_ID = 9_112_511L;
    private static final long INSTRUCTOR_PARTICIPANT_ID = 9_112_520L;
    private static final long STUDENT_PARTICIPANT_ID = 9_112_521L;
    private static final long OTHER_PARTICIPANT_ID = 9_112_522L;
    private static final long LIVE_PARTICIPANT_ID = 9_112_523L;
    private static final long STUDENT_REPORT_ID = 9_112_530L;
    private static final long OTHER_REPORT_ID = 9_112_531L;
    private static final LocalDateTime NOW =
            LocalDateTime.ofInstant(Instant.parse("2026-08-04T01:00:00Z"), ZoneOffset.UTC);

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
        insertMember(INSTRUCTOR_ID, "강사");
        insertMember(STUDENT_ID, "학생");
        insertMember(OTHER_STUDENT_ID, "다른 학생");
        insertMember(OUTSIDER_ID, "외부인");
        insertSession(SESSION_ID, "ENDED", "RP112510");
        insertSession(LIVE_SESSION_ID, "LIVE", "RP112511");
        insertParticipant(INSTRUCTOR_PARTICIPANT_ID, SESSION_ID, INSTRUCTOR_ID, "INSTRUCTOR");
        insertParticipant(STUDENT_PARTICIPANT_ID, SESSION_ID, STUDENT_ID, "STUDENT");
        insertParticipant(OTHER_PARTICIPANT_ID, SESSION_ID, OTHER_STUDENT_ID, "STUDENT");
        insertParticipant(LIVE_PARTICIPANT_ID, LIVE_SESSION_ID, STUDENT_ID, "STUDENT");
    }

    @Test
    @DisplayName("학생은 본인의 활동·참여 요약·정렬된 추천 5개만 조회한다")
    void returns_only_the_calling_students_report() throws Exception {
        insertStudentReport(STUDENT_REPORT_ID, STUDENT_PARTICIPANT_ID, "참여 요약", NOW);
        insertStudentReport(OTHER_REPORT_ID, OTHER_PARTICIPANT_ID, "다른 학생 요약", NOW);
        seedActivity();
        seedRecommendations();
        insertRecommendation(9_112_607L, OTHER_REPORT_ID, "MISSED", "다른 학생 제목", 0L, 1_000L, 0);

        String body = fetchReport(STUDENT_ID, SESSION_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activity.publicChatCount").value(4))
                .andExpect(jsonPath("$.data.activity.confusedCount").value(1))
                .andExpect(jsonPath("$.data.activity.missedCount").value(0))
                .andExpect(jsonPath("$.data.participationSummary").value("참여 요약"))
                .andExpect(jsonPath("$.data.recommendations.length()").value(5))
                .andExpect(
                        jsonPath("$.data.recommendations[0].recommendationType").value("CUSTOM"))
                .andExpect(jsonPath("$.data.recommendations[0].startSeconds").value(10))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain(
                        "participantId",
                        "memberId",
                        "studentId",
                        String.valueOf(OTHER_STUDENT_ID),
                        String.valueOf(OTHER_PARTICIPANT_ID),
                        "다른 학생 요약",
                        "다른 학생 제목");
    }

    @Test
    @DisplayName("인증이 없으면 401이다")
    void rejects_anonymous_access() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/reports/student", SESSION_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("강사는 학생 리포트를 조회할 수 없다")
    void rejects_the_instructor() throws Exception {
        fetchReport(INSTRUCTOR_ID, SESSION_ID).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("세션 비참가자는 세션 존재 여부를 알 수 없는 403이다")
    void hides_the_session_from_an_outsider() throws Exception {
        fetchReport(OUTSIDER_ID, SESSION_ID).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("진행 중 세션은 409다")
    void rejects_a_live_session() throws Exception {
        fetchReport(STUDENT_ID, LIVE_SESSION_ID).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("학생 리포트가 아직 없으면 404다")
    void reports_not_ready_when_the_report_is_missing() throws Exception {
        fetchReport(STUDENT_ID, SESSION_ID)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPORT_002"));
    }

    @Test
    @DisplayName("학생 리포트가 미게시 상태면 404다")
    void reports_not_ready_when_the_report_is_unpublished() throws Exception {
        insertStudentReport(STUDENT_REPORT_ID, STUDENT_PARTICIPANT_ID, "미게시 요약", null);

        fetchReport(STUDENT_ID, SESSION_ID)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPORT_002"));
    }

    private ResultActions fetchReport(long memberId, long sessionId) throws Exception {
        return mockMvc.perform(get("/api/v1/sessions/{sessionId}/reports/student", sessionId)
                .header(
                        "Authorization",
                        "Bearer "
                                + tokenProvider
                                        .issueAccessToken(String.valueOf(memberId))
                                        .value()));
    }

    private void seedActivity() {
        for (int index = 0; index < 4; index++) {
            insertChat(9_112_700L + index, STUDENT_PARTICIPANT_ID, null, "PUBLIC");
        }
        insertChat(9_112_704L, STUDENT_PARTICIPANT_ID, OTHER_PARTICIPANT_ID, "PRIVATE");
        insertChat(9_112_705L, OTHER_PARTICIPANT_ID, null, "PUBLIC");
        insertPrompt(9_112_710L, STUDENT_PARTICIPANT_ID, "CONFUSED");
        insertPrompt(9_112_711L, STUDENT_PARTICIPANT_ID, "OK");
        insertPrompt(9_112_712L, OTHER_PARTICIPANT_ID, "MISSED");
    }

    private void seedRecommendations() {
        insertRecommendation(9_112_600L, STUDENT_REPORT_ID, "QUESTION", "QUESTION 제목", 30_000L, 39_999L, 1);
        insertRecommendation(9_112_601L, STUDENT_REPORT_ID, "CUSTOM", "CUSTOM 제목", 10_999L, 20_999L, 1);
        insertRecommendation(9_112_602L, STUDENT_REPORT_ID, "CONFUSED", "CONFUSED 제목", 20_000L, 29_999L, 2);
        insertRecommendation(9_112_603L, STUDENT_REPORT_ID, "MISSED", "MISSED 제목", 40_000L, 49_999L, 2);
        insertRecommendation(9_112_604L, STUDENT_REPORT_ID, "REPEAT", "REPEAT 제목", 10_000L, 19_999L, 3);
        insertRecommendation(9_112_605L, STUDENT_REPORT_ID, "QUESTION", "제외 1", 1_000L, 9_999L, 4);
        insertRecommendation(9_112_606L, STUDENT_REPORT_ID, "QUESTION", "제외 2", 0L, 999L, 5);
    }

    private void insertMember(long id, String name) {
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                id,
                "student-report-api-" + id,
                id + "@student-report-api.test",
                name,
                NOW,
                NOW);
    }

    private void insertSession(long id, String status, String inviteCode) {
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, ended_at, created_at, updated_at) VALUES (?, ?, '학생 리포트 API 테스트', ?, ?,"
                        + " 'COMPLETED', ?, ?, ?, ?)",
                id,
                INSTRUCTOR_ID,
                inviteCode,
                status,
                NOW.minusHours(1),
                "ENDED".equals(status) ? NOW : null,
                NOW,
                NOW);
    }

    private void insertParticipant(long id, long sessionId, long memberId, String role) {
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at,"
                        + " last_accessed_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                sessionId,
                memberId,
                role,
                NOW,
                NOW,
                NOW,
                NOW);
    }

    private void insertStudentReport(long id, long participantId, String summary, LocalDateTime publishedAt) {
        jdbcTemplate.update(
                "INSERT INTO student_reports (id, session_id, session_participant_id, participation_summary,"
                        + " published_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id,
                SESSION_ID,
                participantId,
                summary,
                publishedAt,
                NOW,
                NOW);
    }

    private void insertChat(long id, long senderParticipantId, Long recipientParticipantId, String channelType) {
        jdbcTemplate.update(
                "INSERT INTO chat_messages (id, session_id, sender_participant_id, recipient_participant_id,"
                        + " channel_type, content, occurred_offset_ms, created_at) VALUES (?, ?, ?, ?, ?, '메시지', 1000, ?)",
                id,
                SESSION_ID,
                senderParticipantId,
                recipientParticipantId,
                channelType,
                NOW);
    }

    private void insertPrompt(long id, long participantId, String response) {
        jdbcTemplate.update(
                "INSERT INTO check_prompts (id, session_id, session_participant_id, trigger_type, status, response,"
                        + " shown_offset_ms, responded_offset_ms, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'LOW_ENGAGEMENT', 'RESPONDED', ?, 1000, 2000, ?, ?)",
                id,
                SESSION_ID,
                participantId,
                response,
                NOW,
                NOW);
    }

    private void insertRecommendation(
            long id, long reportId, String type, String title, long startMs, long endMs, int priority) {
        jdbcTemplate.update(
                "INSERT INTO review_recommendations (id, student_report_id, recommendation_type, title,"
                        + " description, started_offset_ms, ended_offset_ms, priority, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                reportId,
                type,
                title,
                type + " 설명",
                startMs,
                endMs,
                priority,
                NOW,
                NOW);
    }
}
