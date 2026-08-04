package com.a105.zani.report.infrastructure.persistence.query;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.report.application.getstudentreport.StudentReportView;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class StudentReportQueryAdapterTest {

    private static final long INSTRUCTOR_ID = 9_112_000L;
    private static final long STUDENT_ID = 9_112_001L;
    private static final long OTHER_STUDENT_ID = 9_112_002L;
    private static final long SESSION_ID = 9_112_010L;
    private static final long INSTRUCTOR_PARTICIPANT_ID = 9_112_020L;
    private static final long STUDENT_PARTICIPANT_ID = 9_112_021L;
    private static final long OTHER_PARTICIPANT_ID = 9_112_022L;
    private static final long STUDENT_REPORT_ID = 9_112_030L;
    private static final long OTHER_REPORT_ID = 9_112_031L;
    private static final LocalDateTime NOW =
            LocalDateTime.ofInstant(Instant.parse("2026-08-04T01:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private StudentReportQueryAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        insertMember(INSTRUCTOR_ID, "강사");
        insertMember(STUDENT_ID, "학생");
        insertMember(OTHER_STUDENT_ID, "다른 학생");
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, ended_at, created_at, updated_at)"
                        + " VALUES (?, ?, '학생 리포트 projection 테스트', 'RP112010', 'ENDED', 'COMPLETED', ?, ?, ?, ?)",
                SESSION_ID,
                INSTRUCTOR_ID,
                NOW.minusHours(1),
                NOW,
                NOW,
                NOW);
        insertParticipant(INSTRUCTOR_PARTICIPANT_ID, INSTRUCTOR_ID, "INSTRUCTOR");
        insertParticipant(STUDENT_PARTICIPANT_ID, STUDENT_ID, "STUDENT");
        insertParticipant(OTHER_PARTICIPANT_ID, OTHER_STUDENT_ID, "STUDENT");
    }

    @Test
    @DisplayName("본인의 공개 채팅·응답·게시 리포트와 정렬된 추천 5개만 조회한다")
    void projects_only_the_students_published_report() {
        insertStudentReport(STUDENT_REPORT_ID, STUDENT_PARTICIPANT_ID, "참여 요약", NOW);
        insertStudentReport(OTHER_REPORT_ID, OTHER_PARTICIPANT_ID, "다른 학생 요약", NOW);

        for (int index = 0; index < 4; index++) {
            insertChat(9_112_100L + index, STUDENT_PARTICIPANT_ID, null, "PUBLIC");
        }
        insertChat(9_112_104L, STUDENT_PARTICIPANT_ID, OTHER_PARTICIPANT_ID, "PRIVATE");
        insertChat(9_112_105L, OTHER_PARTICIPANT_ID, null, "PUBLIC");

        insertPrompt(9_112_200L, STUDENT_PARTICIPANT_ID, "CONFUSED");
        insertPrompt(9_112_201L, STUDENT_PARTICIPANT_ID, "OK");
        insertPrompt(9_112_202L, STUDENT_PARTICIPANT_ID, "NO_RESPONSE");
        insertPrompt(9_112_203L, STUDENT_PARTICIPANT_ID, null);
        insertPrompt(9_112_204L, OTHER_PARTICIPANT_ID, "MISSED");

        insertRecommendation(9_112_300L, STUDENT_REPORT_ID, "QUESTION", 30_000L, 39_999L, 1);
        insertRecommendation(9_112_301L, STUDENT_REPORT_ID, "CUSTOM", 10_999L, 20_999L, 1);
        insertRecommendation(9_112_302L, STUDENT_REPORT_ID, "CONFUSED", 20_000L, 29_999L, 2);
        insertRecommendation(9_112_303L, STUDENT_REPORT_ID, "MISSED", 40_000L, 49_999L, 2);
        insertRecommendation(9_112_304L, STUDENT_REPORT_ID, "REPEAT", 10_000L, 19_999L, 3);
        insertRecommendation(9_112_305L, STUDENT_REPORT_ID, "QUESTION", 1_000L, 9_999L, 4);
        insertRecommendation(9_112_306L, STUDENT_REPORT_ID, "QUESTION", 0L, 999L, 5);
        insertRecommendation(9_112_307L, OTHER_REPORT_ID, "MISSED", 0L, 1_000L, 0);

        assertThat(adapter.findBySessionIdAndParticipantId(SESSION_ID, STUDENT_PARTICIPANT_ID))
                .contains(new StudentReportView(
                        4L,
                        1L,
                        0L,
                        "참여 요약",
                        List.of(
                                recommendation("CUSTOM", 10L, 20L, 1),
                                recommendation("QUESTION", 30L, 39L, 1),
                                recommendation("CONFUSED", 20L, 29L, 2),
                                recommendation("MISSED", 40L, 49L, 2),
                                recommendation("REPEAT", 10L, 19L, 3))));
    }

    @Test
    @DisplayName("활동 행이 없어도 집계값은 0이다")
    void returns_zero_activity_counts_when_no_rows_exist() {
        insertStudentReport(STUDENT_REPORT_ID, STUDENT_PARTICIPANT_ID, "참여 요약", NOW);

        assertThat(adapter.findBySessionIdAndParticipantId(SESSION_ID, STUDENT_PARTICIPANT_ID))
                .contains(new StudentReportView(0L, 0L, 0L, "참여 요약", List.of()));
    }

    @Test
    @DisplayName("리포트가 없거나 아직 게시되지 않았으면 projection이 없다")
    void hides_missing_and_unpublished_reports() {
        insertStudentReport(STUDENT_REPORT_ID, STUDENT_PARTICIPANT_ID, "미게시 요약", null);
        insertStudentReport(OTHER_REPORT_ID, OTHER_PARTICIPANT_ID, "다른 학생 요약", NOW);

        assertThat(adapter.findBySessionIdAndParticipantId(SESSION_ID, STUDENT_PARTICIPANT_ID))
                .isEmpty();
    }

    private static StudentReportView.Recommendation recommendation(
            String type, long startSeconds, long endSeconds, int priority) {
        return new StudentReportView.Recommendation(
                type, type + " 제목", type + " 설명", startSeconds, endSeconds, priority);
    }

    private void insertMember(long id, String name) {
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                id,
                "report-google-" + id,
                id + "@report.test",
                name,
                NOW,
                NOW);
    }

    private void insertParticipant(long id, long memberId, String role) {
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at,"
                        + " last_accessed_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                SESSION_ID,
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
                "INSERT INTO check_prompts (id, session_id, session_participant_id, trigger_type, status,"
                        + " response, shown_offset_ms, responded_offset_ms, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'LOW_ENGAGEMENT', ?, ?, 1000, ?, ?, ?)",
                id,
                SESSION_ID,
                participantId,
                response == null ? "OPEN" : "RESPONDED",
                response,
                response == null ? null : 2_000L,
                NOW,
                NOW);
    }

    private void insertRecommendation(
            long id, long reportId, String type, long startedOffsetMs, long endedOffsetMs, int priority) {
        jdbcTemplate.update(
                "INSERT INTO review_recommendations (id, student_report_id, recommendation_type, title,"
                        + " description, started_offset_ms, ended_offset_ms, priority, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                reportId,
                type,
                type + " 제목",
                type + " 설명",
                startedOffsetMs,
                endedOffsetMs,
                priority,
                NOW,
                NOW);
    }
}
