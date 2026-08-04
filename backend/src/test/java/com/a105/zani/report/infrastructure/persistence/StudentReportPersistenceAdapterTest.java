package com.a105.zani.report.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.report.application.savestudentanalysis.SaveStudentAnalysisCommand;
import com.a105.zani.report.application.savestudentanalysis.SaveStudentAnalysisUseCase;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class StudentReportPersistenceAdapterTest {

    private static final Instant SESSION_STARTED_AT = Instant.parse("2026-08-03T01:00:00Z");

    @Autowired
    private SaveStudentAnalysisUseCase saveStudentAnalysisUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void storesReportAndRecommendationsWithPriorityFromOne() {
        long sessionId = insertSession();
        long participantId = insertStudent(sessionId);

        Optional<Long> reportId = saveStudentAnalysisUseCase.save(command(sessionId, participantId, 2));

        assertThat(reportId).isPresent();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT participation_summary FROM student_reports WHERE id = ?", String.class, reportId.get()))
                .isEqualTo("참여도 요약");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT published_at FROM student_reports WHERE id = ?", Instant.class, reportId.get()))
                .isNull();
        // 질문 수는 모델이 판단한 값이다 — 서버가 채팅 행을 세지 않는다.
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT question_count FROM student_reports WHERE id = ?", Integer.class, reportId.get()))
                .isEqualTo(3);
        assertThat(jdbcTemplate.queryForList(
                        "SELECT priority FROM review_recommendations WHERE student_report_id = ? ORDER BY priority",
                        Integer.class,
                        reportId.get()))
                .containsExactly(1, 2);
        assertThat(jdbcTemplate.queryForList(
                        "SELECT started_offset_ms FROM review_recommendations WHERE student_report_id = ?"
                                + " ORDER BY priority",
                        Long.class,
                        reportId.get()))
                .containsExactly(0L, 60_000L);
    }

    @Test
    void returnsEmptyAndAddsNoRecommendationWhenAnotherRunStoredFirst() {
        long sessionId = insertSession();
        long participantId = insertStudent(sessionId);

        Optional<Long> first = saveStudentAnalysisUseCase.save(command(sessionId, participantId, 2));
        Optional<Long> second = saveStudentAnalysisUseCase.save(command(sessionId, participantId, 2));

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM student_reports WHERE session_id = ? AND session_participant_id = ?",
                        Integer.class,
                        sessionId,
                        participantId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM review_recommendations WHERE student_report_id = ?",
                        Integer.class,
                        first.get()))
                .isEqualTo(2);
    }

    @Test
    void storesReportWithoutRecommendations() {
        long sessionId = insertSession();
        long participantId = insertStudent(sessionId);

        Optional<Long> reportId = saveStudentAnalysisUseCase.save(command(sessionId, participantId, 0));

        assertThat(reportId).isPresent();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM review_recommendations WHERE student_report_id = ?",
                        Integer.class,
                        reportId.get()))
                .isZero();
    }

    private SaveStudentAnalysisCommand command(long sessionId, long participantId, int recommendationCount) {
        List<SaveStudentAnalysisCommand.Recommendation> recommendations = List.of(
                        new SaveStudentAnalysisCommand.Recommendation("CONFUSED", "이차방정식", "다시 보기", 0L, 30_000L),
                        new SaveStudentAnalysisCommand.Recommendation("LOW_ENGAGEMENT", "인수분해", "복습", 60_000L, 90_000L))
                .subList(0, recommendationCount);
        return new SaveStudentAnalysisCommand(sessionId, participantId, "참여도 요약", 3, recommendations);
    }

    private long insertSession() {
        long memberId = TsidGenerator.generate();
        long sessionId = TsidGenerator.generate();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                memberId,
                "student-report-" + suffix,
                "student-report-" + suffix + "@example.com",
                "student report test");
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status, started_at,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'ENDED', 'PROCESSING', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                sessionId,
                memberId,
                "개인 리포트 저장 테스트",
                suffix,
                java.sql.Timestamp.from(SESSION_STARTED_AT));
        return sessionId;
    }

    private long insertStudent(long sessionId) {
        long memberId = TsidGenerator.generate();
        long participantId = TsidGenerator.generate();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                memberId,
                "student-" + suffix,
                "student-" + suffix + "@example.com",
                "student");
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at, created_at,"
                        + " updated_at) VALUES (?, ?, ?, 'STUDENT', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),"
                        + " UTC_TIMESTAMP(6))",
                participantId,
                sessionId,
                memberId);
        return participantId;
    }
}
