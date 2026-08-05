package com.a105.zani.quiz.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.quiz.domain.model.Quiz;
import com.a105.zani.quiz.domain.model.QuizOption;
import com.a105.zani.quiz.domain.model.QuizQuestion;
import com.a105.zani.quiz.domain.repository.QuizRepository;

/**
 * 퀴즈·문항·보기 세 테이블을 한 번에 넣는다.
 *
 * <p>멱등은 UK_QUIZZES_STUDENT_REPORT 에 맡긴다. 넣은 뒤 저장된 ID 를 되읽어 우리 TSID 가 아니면 다른 실행의 퀴즈이므로 문항을 덧붙이지 않는다.
 *
 * <p>{@code estimated_duration_minutes} 는 모델이 답한 값이 아니라 {@link Quiz} 가 문항 수로 계산한 값이다. 조회 API(S15P11A105-255)가 이 필드를 그대로
 * 내보내므로 비워 두면 실제 분석 결과에서만 빈칸이 된다.
 */
@Component
@RequiredArgsConstructor
public class QuizPersistenceAdapter implements QuizRepository {

    private static final String INSERT_QUIZ = """
            INSERT INTO quizzes
                (id, student_report_id, title, description, estimated_duration_minutes, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE id = id
            """;

    private static final String SELECT_QUIZ_ID = """
            SELECT id FROM quizzes WHERE student_report_id = ?
            """;

    private static final String INSERT_QUESTION = """
            INSERT INTO quiz_questions
                (id, quiz_id, question_text, explanation, section_started_offset_ms, question_order,
                 created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            """;

    private static final String INSERT_OPTION = """
            INSERT INTO quiz_options
                (id, quiz_question_id, option_text, is_correct, option_order, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean saveIfAbsent(Quiz quiz) {
        long quizId = TsidGenerator.generate();
        jdbcTemplate.update(
                INSERT_QUIZ,
                quizId,
                quiz.studentReportId(),
                quiz.title(),
                quiz.description(),
                quiz.estimatedDurationMinutes());

        Long storedId = jdbcTemplate.queryForObject(SELECT_QUIZ_ID, Long.class, quiz.studentReportId());
        if (storedId == null || storedId.longValue() != quizId) {
            return false;
        }

        List<Object[]> questionRows = new ArrayList<>();
        List<Object[]> optionRows = new ArrayList<>();
        for (QuizQuestion question : quiz.questions()) {
            long questionId = TsidGenerator.generate();
            questionRows.add(new Object[] {
                questionId,
                quizId,
                question.questionText(),
                question.explanation(),
                question.sectionStartedOffsetMs(),
                question.questionOrder()
            });
            for (QuizOption option : question.options()) {
                optionRows.add(new Object[] {
                    TsidGenerator.generate(), questionId, option.optionText(), option.correct(), option.optionOrder()
                });
            }
        }
        jdbcTemplate.batchUpdate(INSERT_QUESTION, questionRows);
        jdbcTemplate.batchUpdate(INSERT_OPTION, optionRows);
        return true;
    }
}
