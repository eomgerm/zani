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
import static org.hamcrest.Matchers.nullValue;
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
    private static final long SESSION_REPORT_ID = 9_112_540L;
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
        // 공개 게이트는 공통 리포트의 게시다. 개인 리포트 행의 published_at 이 아니다(S15P11A105-310).
        publishSessionReport();
    }

    @Test
    @DisplayName("학생은 본인의 활동·참여 요약·정렬된 추천 5개만 조회한다")
    void returns_only_the_calling_students_report() throws Exception {
        insertStudentReport(STUDENT_REPORT_ID, STUDENT_PARTICIPANT_ID, "참여 요약", 2);
        insertStudentReport(OTHER_REPORT_ID, OTHER_PARTICIPANT_ID, "다른 학생 요약", 9);
        seedActivity();
        seedRecommendations();
        insertRecommendation(9_112_607L, OTHER_REPORT_ID, "MISSED", "다른 학생 제목", 0L, 1_000L, 0);

        String body = fetchReport(STUDENT_ID, SESSION_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activity.publicChatCount").value(4))
                .andExpect(jsonPath("$.data.activity.confusedCount").value(1))
                .andExpect(jsonPath("$.data.activity.missedCount").value(0))
                .andExpect(jsonPath("$.data.activity.questionCount").value(2))
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
    @DisplayName("질문 수 판정이 없으면 0이 아니라 null로 내린다")
    void sends_a_null_question_count_when_the_analysis_has_none() throws Exception {
        insertStudentReport(STUDENT_REPORT_ID, STUDENT_PARTICIPANT_ID, "참여 요약", null);
        seedActivity();

        // 0 으로 내리면 화면이 "0개" 를 적어 질문을 안 한 학생과 구분되지 않는다. 세는 값이 아니라
        // 모델이 판단한 값이므로 판정이 없다는 사실을 그대로 전한다.
        fetchReport(STUDENT_ID, SESSION_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activity.publicChatCount").value(4))
                .andExpect(jsonPath("$.data.activity.questionCount").value(nullValue()));
    }

    @Test
    @DisplayName("복습 클립이 쓰는 재생 정보를 함께 내린다 — 녹화 파일이 없으면 recordingUrl만 null이다")
    void returns_the_clip_playback_contract() throws Exception {
        insertStudentReport(STUDENT_REPORT_ID, STUDENT_PARTICIPANT_ID, "참여 요약", 2);
        seedRecommendations();
        insertTranscript();

        fetchReport(STUDENT_ID, SESSION_ID)
                .andExpect(status().isOk())
                // 최종 MP4 가 아직 없는 환경이다. 리포트 전체가 404 로 죽지 않고 이 필드만 비어야 한다.
                .andExpect(jsonPath("$.data.recordingUrl").value(nullValue()))
                // 세션 시작 1시간 전 ~ 종료 = 3,600 초
                .andExpect(jsonPath("$.data.durationSeconds").value(3600))
                .andExpect(jsonPath("$.data.seekTimestamp").value(0))
                .andExpect(jsonPath("$.data.transcript.length()").value(2))
                // 화자는 실명이다. 익명 별칭이 보이면 계약 위반이다(REPORT-S-001).
                .andExpect(jsonPath("$.data.transcript[0].speakerName").value("강사"))
                .andExpect(jsonPath("$.data.transcript[0].startSeconds").value(1))
                .andExpect(jsonPath("$.data.transcript[0].text").value("강사 발화"))
                .andExpect(jsonPath("$.data.transcript[1].speakerName").value("학생"))
                .andExpect(jsonPath("$.data.transcript[1].startSeconds").value(20))
                // 추천 식별자는 TSID 라 문자열로 내려야 JS 안전 정수 범위를 넘겨도 값이 뭉개지지 않는다.
                .andExpect(jsonPath("$.data.recommendations[0].id").value("9112601"))
                .andExpect(jsonPath("$.data.recommendations[0].reason").value("CUSTOM 설명"));
    }

    @Test
    @DisplayName("전사가 아직 없으면 빈 배열이며 오류가 아니다")
    void returns_an_empty_transcript_when_none_exists() throws Exception {
        insertStudentReport(STUDENT_REPORT_ID, STUDENT_PARTICIPANT_ID, "참여 요약", 2);

        fetchReport(STUDENT_ID, SESSION_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transcript.length()").value(0));
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

    /** 행은 만들어졌지만 아직 공개 전이다. 중간 상태를 화면에 내보내면 반쯤 만들어진 리포트를 읽게 된다. */
    @Test
    @DisplayName("공통 리포트가 미게시 상태면 404다")
    void reports_not_ready_when_the_session_report_is_unpublished() throws Exception {
        unpublishSessionReport();
        insertStudentReport(STUDENT_REPORT_ID, STUDENT_PARTICIPANT_ID, "미게시 요약");

        fetchReport(STUDENT_ID, SESSION_ID)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPORT_002"));
    }

    /**
     * 이 티켓이 고친 것 — 개인 리포트 행의 {@code published_at} 이 비어 있어도 공통 리포트가 게시됐으면 보인다.
     *
     * <p>운영에서 그 컬럼을 채우는 코드가 없다. 공개 단계는 {@code session_reports} 에만 시각을 찍으므로(S15P11A105-304), 그 컬럼을 게이트로 두면 분석이 정상 완주해도
     * 학생은 영구히 리포트를 열 수 없다. 강사 리포트가 같은 이유로 막혀 있었다(S15P11A105-310).
     */
    @Test
    @DisplayName("개인 리포트 행의 공개 시각이 비어도 공통 리포트가 게시됐으면 200이다")
    void serves_the_report_even_when_the_row_has_no_published_at() throws Exception {
        insertStudentReport(STUDENT_REPORT_ID, STUDENT_PARTICIPANT_ID, "참여 요약");

        fetchReport(STUDENT_ID, SESSION_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.participationSummary").value("참여 요약"));

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT published_at FROM student_reports WHERE id = ?", Object.class, STUDENT_REPORT_ID))
                .as("개인 리포트 행의 공개 시각은 여전히 비어 있어야 한다 — 이 값이 노출을 정하지 않는다는 것이 이 테스트의 전제다")
                .isNull();
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

    /** 화자 키는 참가자 ID 다. 실명으로 푸는 일은 서버가 조립 시점에 끝낸다 — 이름을 JSON 에 굳히지 않는다. */
    private void insertTranscript() {
        jdbcTemplate.update(
                "INSERT INTO transcripts (id, session_id, transcript_document, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                9_112_800L,
                SESSION_ID,
                """
                {
                  "schemaVersion": 1,
                  "segments": [
                    {"sessionParticipantId": %d, "startOffsetMs": 20000, "endOffsetMs": 25400, "text": "학생 발화"},
                    {"sessionParticipantId": %d, "startOffsetMs": 1500, "endOffsetMs": 9900, "text": "강사 발화"}
                  ]
                }
                """.formatted(STUDENT_PARTICIPANT_ID, INSTRUCTOR_PARTICIPANT_ID),
                NOW,
                NOW);
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

    /** 질문 수는 모델이 채우는 값이라 없는 리포트가 정상이다. 따로 주지 않으면 비워 둔다. */
    private void insertStudentReport(long id, long participantId, String summary) {
        insertStudentReport(id, participantId, summary, null);
    }

    /**
     * 개인 리포트 행.
     *
     * <p><b>{@code published_at} 을 인자로 받지 않는다 — 언제나 비운다.</b> 운영에서 이 컬럼을 채우는 코드가 없기 때문이다. 예전 이 파일은 값을 직접 넣어 200 을 받아 냈고,
     * 그래서 "분석이 완주해도 학생 리포트가 영구히 404" 라는 사실을 놓쳤다. 테스트가 만들 수 있는 상태는 운영이 만들 수 있는 상태여야 한다.
     */
    private void insertStudentReport(long id, long participantId, String summary, Integer questionCount) {
        jdbcTemplate.update(
                "INSERT INTO student_reports (id, session_id, session_participant_id, participation_summary,"
                        + " question_count, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id,
                SESSION_ID,
                participantId,
                summary,
                questionCount,
                NOW,
                NOW);
    }

    /** 공개 게이트. 강사 리포트·수업 클립·수업 요약이 모두 이 값 하나를 본다. */
    private void publishSessionReport() {
        jdbcTemplate.update(
                "INSERT INTO session_reports (id, session_id, summary, published_at, created_at, updated_at)"
                        + " VALUES (?, ?, '수업 공통 요약', ?, ?, ?)",
                SESSION_REPORT_ID,
                SESSION_ID,
                NOW,
                NOW,
                NOW);
    }

    /** 분석은 끝나 공통 리포트가 생겼지만 아직 공개되지 않은 상태로 되돌린다. */
    private void unpublishSessionReport() {
        jdbcTemplate.update("UPDATE session_reports SET published_at = NULL WHERE id = ?", SESSION_REPORT_ID);
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
