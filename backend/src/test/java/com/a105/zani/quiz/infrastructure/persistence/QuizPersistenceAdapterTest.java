package com.a105.zani.quiz.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.quiz.application.creategeneratedquiz.CreateGeneratedQuizCommand;
import com.a105.zani.quiz.application.creategeneratedquiz.CreateGeneratedQuizUseCase;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class QuizPersistenceAdapterTest {

    @Autowired
    private CreateGeneratedQuizUseCase createGeneratedQuizUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void storesQuizQuestionsAndOptionsAtomically() {
        long reportId = insertStudentReport();

        boolean created = createGeneratedQuizUseCase.create(command(reportId, 3));

        assertThat(created).isTrue();
        Long quizId =
                jdbcTemplate.queryForObject("SELECT id FROM quizzes WHERE student_report_id = ?", Long.class, reportId);
        assertThat(jdbcTemplate.queryForObject("SELECT published_at FROM quizzes WHERE id = ?", Instant.class, quizId))
                .isNull();
        // 조회 API(255)가 이 필드를 내보내므로 생성 경로에서 비어 있으면 실데이터에서만 빈칸이 된다.
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT estimated_duration_minutes FROM quizzes WHERE id = ?", Integer.class, quizId))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
                        "SELECT question_order FROM quiz_questions WHERE quiz_id = ? ORDER BY question_order",
                        Integer.class,
                        quizId))
                .containsExactly(1, 2, 3);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM quiz_options o JOIN quiz_questions q ON q.id = o.quiz_question_id"
                                + " WHERE q.quiz_id = ?",
                        Integer.class,
                        quizId))
                .isEqualTo(12);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM quiz_options o JOIN quiz_questions q ON q.id = o.quiz_question_id"
                                + " WHERE q.quiz_id = ? AND o.is_correct = TRUE",
                        Integer.class,
                        quizId))
                .isEqualTo(3);
    }

    @Test
    void returnsFalseWhenAnotherRunStoredFirst() {
        long reportId = insertStudentReport();

        boolean first = createGeneratedQuizUseCase.create(command(reportId, 3));
        boolean second = createGeneratedQuizUseCase.create(command(reportId, 3));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM quizzes WHERE student_report_id = ?", Integer.class, reportId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM quiz_questions q JOIN quizzes z ON z.id = q.quiz_id"
                                + " WHERE z.student_report_id = ?",
                        Integer.class,
                        reportId))
                .isEqualTo(3);
    }

    private CreateGeneratedQuizCommand command(long reportId, int questionCount) {
        List<CreateGeneratedQuizCommand.Question> questions = IntStream.range(0, questionCount)
                .mapToObj(index -> new CreateGeneratedQuizCommand.Question(
                        "문항 " + index,
                        "해설",
                        List.of(
                                new CreateGeneratedQuizCommand.Option("정답", true),
                                new CreateGeneratedQuizCommand.Option("오답1", false),
                                new CreateGeneratedQuizCommand.Option("오답2", false),
                                new CreateGeneratedQuizCommand.Option("오답3", false))))
                .toList();
        return new CreateGeneratedQuizCommand(reportId, "퀴즈", "설명", questions);
    }

    /** 퀴즈는 student_reports 를 FK 로 참조한다. 리포트·참여자·세션·회원 네 행을 먼저 만든다. */
    private long insertStudentReport() {
        long memberId = TsidGenerator.generate();
        long sessionId = TsidGenerator.generate();
        long participantId = TsidGenerator.generate();
        long reportId = TsidGenerator.generate();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                memberId,
                "quiz-" + suffix,
                "quiz-" + suffix + "@example.com",
                "quiz test");
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status, started_at,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'ENDED', 'PROCESSING', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),"
                        + " UTC_TIMESTAMP(6))",
                sessionId,
                memberId,
                "퀴즈 저장 테스트",
                suffix);
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at, created_at,"
                        + " updated_at) VALUES (?, ?, ?, 'STUDENT', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),"
                        + " UTC_TIMESTAMP(6))",
                participantId,
                sessionId,
                memberId);
        jdbcTemplate.update(
                "INSERT INTO student_reports (id, session_id, session_participant_id, participation_summary,"
                        + " created_at, updated_at) VALUES (?, ?, ?, '요약', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                reportId,
                sessionId,
                participantId);
        return reportId;
    }
}
