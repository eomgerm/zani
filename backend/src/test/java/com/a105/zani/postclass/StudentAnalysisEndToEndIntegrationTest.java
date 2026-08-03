package com.a105.zani.postclass;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.postclass.application.analyzestudents.AnalyzeSessionStudentsCommand;
import com.a105.zani.postclass.application.analyzestudents.AnalyzeSessionStudentsResult;
import com.a105.zani.postclass.application.analyzestudents.AnalyzeSessionStudentsUseCase;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 학생 3명 세션을 통째로 태워 각자에게 요약·추천·퀴즈가 생기고 타인 데이터와 섞이지 않는지 본다.
 *
 * <p>{@code gms.mock-enabled} 기본값이 true 라 Mock 어댑터가 뜬다. 실제 GMS 는 자동 테스트에서 때리지 않는다.
 */
@SpringBootTest
@Transactional
class StudentAnalysisEndToEndIntegrationTest {

    @Autowired
    private AnalyzeSessionStudentsUseCase analyzeSessionStudentsUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void givesEveryStudentTheirOwnReportAndQuiz() {
        long hostMemberId = insertMember();
        long sessionId = insertSession(hostMemberId);
        insertSessionReport(sessionId);
        insertSection(sessionId, "근의 공식", 0L, 60_000L);
        insertSection(sessionId, "인수분해", 60_000L, 120_000L);
        List<Long> students =
                List.of(insertParticipant(sessionId), insertParticipant(sessionId), insertParticipant(sessionId));
        insertChatMessage(sessionId, students.getFirst(), "첫째 학생 발화");

        AnalyzeSessionStudentsResult result =
                analyzeSessionStudentsUseCase.analyze(new AnalyzeSessionStudentsCommand(sessionId));

        assertThat(result.analyzed()).isEqualTo(3);
        assertThat(result.failedParticipantIds()).isEmpty();
        assertThat(jdbcTemplate.queryForList(
                        "SELECT session_participant_id FROM student_reports WHERE session_id = ?"
                                + " ORDER BY session_participant_id",
                        Long.class,
                        sessionId))
                .containsExactlyElementsOf(students.stream().sorted().toList());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM quizzes q JOIN student_reports r ON r.id = q.student_report_id"
                                + " WHERE r.session_id = ?",
                        Integer.class,
                        sessionId))
                .isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM quiz_options o"
                                + " JOIN quiz_questions qq ON qq.id = o.quiz_question_id"
                                + " JOIN quizzes q ON q.id = qq.quiz_id"
                                + " JOIN student_reports r ON r.id = q.student_report_id"
                                + " WHERE r.session_id = ? AND o.is_correct = TRUE",
                        Integer.class,
                        sessionId))
                .isEqualTo(9);
        // 추천 시각은 구간 값이어야 한다 — 모델이 준 숫자가 아니다.
        assertThat(jdbcTemplate.queryForList(
                        "SELECT DISTINCT started_offset_ms FROM review_recommendations rr"
                                + " JOIN student_reports r ON r.id = rr.student_report_id WHERE r.session_id = ?",
                        Long.class,
                        sessionId))
                .containsExactly(0L);
        // 공개는 249 의 범위 밖이다. 저장만 하고 published_at 은 비워 둔다.
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM student_reports WHERE session_id = ? AND published_at IS NOT NULL",
                        Integer.class,
                        sessionId))
                .isZero();
    }

    @Test
    void skipsStudentsThatAlreadyHaveAReportOnTheSecondRun() {
        long hostMemberId = insertMember();
        long sessionId = insertSession(hostMemberId);
        insertSessionReport(sessionId);
        insertSection(sessionId, "근의 공식", 0L, 60_000L);
        insertParticipant(sessionId);
        insertParticipant(sessionId);

        AnalyzeSessionStudentsResult first =
                analyzeSessionStudentsUseCase.analyze(new AnalyzeSessionStudentsCommand(sessionId));
        AnalyzeSessionStudentsResult second =
                analyzeSessionStudentsUseCase.analyze(new AnalyzeSessionStudentsCommand(sessionId));

        assertThat(first.analyzed()).isEqualTo(2);
        // 두 번째 실행에서는 대상 자체가 없다 — 리포트가 있는 학생은 조회에서 빠진다.
        assertThat(second.analyzed()).isZero();
        assertThat(second.skipped()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM student_reports WHERE session_id = ?", Integer.class, sessionId))
                .isEqualTo(2);
    }

    private long insertMember() {
        long memberId = TsidGenerator.generate();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                memberId,
                "e2e-" + suffix,
                "e2e-" + suffix + "@example.com",
                "e2e test");
        return memberId;
    }

    private long insertSession(long hostMemberId) {
        long sessionId = TsidGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status, started_at,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'ENDED', 'PROCESSING', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),"
                        + " UTC_TIMESTAMP(6))",
                sessionId,
                hostMemberId,
                "이차방정식 수업",
                UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        return sessionId;
    }

    private void insertSessionReport(long sessionId) {
        jdbcTemplate.update(
                "INSERT INTO session_reports (id, session_id, summary, created_at, updated_at)"
                        + " VALUES (?, ?, '공통 요약', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                TsidGenerator.generate(),
                sessionId);
    }

    private void insertSection(long sessionId, String title, long startedMs, long endedMs) {
        jdbcTemplate.update(
                "INSERT INTO session_sections (id, session_id, title, summary, started_offset_ms, ended_offset_ms,"
                        + " created_at, updated_at) VALUES (?, ?, ?, '구간 요약', ?, ?, UTC_TIMESTAMP(6),"
                        + " UTC_TIMESTAMP(6))",
                TsidGenerator.generate(),
                sessionId,
                title,
                startedMs,
                endedMs);
    }

    private long insertParticipant(long sessionId) {
        long memberId = insertMember();
        long participantId = TsidGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at, created_at,"
                        + " updated_at) VALUES (?, ?, ?, 'STUDENT', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),"
                        + " UTC_TIMESTAMP(6))",
                participantId,
                sessionId,
                memberId);
        return participantId;
    }

    private void insertChatMessage(long sessionId, long participantId, String content) {
        jdbcTemplate.update(
                "INSERT INTO chat_messages (id, session_id, sender_participant_id, channel_type, content,"
                        + " occurred_offset_ms, created_at)"
                        + " VALUES (?, ?, ?, 'PUBLIC', ?, 10000, UTC_TIMESTAMP(6))",
                TsidGenerator.generate(),
                sessionId,
                participantId,
                content);
    }
}
