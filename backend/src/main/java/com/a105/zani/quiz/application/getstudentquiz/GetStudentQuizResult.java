package com.a105.zani.quiz.application.getstudentquiz;

import java.util.List;

import com.a105.zani.quiz.application.port.QuizOptionSnapshot;
import com.a105.zani.quiz.application.port.QuizQuestionSnapshot;
import com.a105.zani.quiz.application.port.StudentQuizSnapshot;

/**
 * 학생에게 보여줄 퀴즈. 정답·해설은 문항의 {@code grading} 안에만 있고, 제출 전에는 {@code grading} 이 {@code null} 이다 — 보기 목록에는 정답 여부가 아예 실리지 않으므로
 * 제출 전 응답으로는 정답을 알아낼 수 없다.
 */
public record GetStudentQuizResult(
        Long quizId,
        String title,
        String description,
        Short estimatedDurationMinutes,
        boolean submitted,
        List<QuestionResult> questions) {

    public static GetStudentQuizResult from(StudentQuizSnapshot snapshot) {
        boolean submitted = snapshot.submitted();
        return new GetStudentQuizResult(
                snapshot.quizId(),
                snapshot.title(),
                snapshot.description(),
                snapshot.estimatedDurationMinutes(),
                submitted,
                snapshot.questions().stream()
                        .map(question -> QuestionResult.from(question, submitted))
                        .toList());
    }

    public record QuestionResult(
            Long questionId, int order, String text, List<OptionResult> options, QuestionGrading grading) {

        private static QuestionResult from(QuizQuestionSnapshot question, boolean submitted) {
            return new QuestionResult(
                    question.questionId(),
                    question.order(),
                    question.text(),
                    question.options().stream().map(OptionResult::from).toList(),
                    submitted ? QuestionGrading.from(question) : null);
        }
    }

    public record OptionResult(Long optionId, int order, String text) {

        private static OptionResult from(QuizOptionSnapshot option) {
            return new OptionResult(option.optionId(), option.order(), option.text());
        }
    }

    /** 제출 후에만 채워지는 문항별 채점 — 정오·내 선택·정답·해설. */
    public record QuestionGrading(boolean correct, Long selectedOptionId, Long correctOptionId, String explanation) {

        private static QuestionGrading from(QuizQuestionSnapshot question) {
            return new QuestionGrading(
                    question.answeredCorrectly(),
                    question.selectedOptionId(),
                    question.correctOptionId(),
                    question.explanation());
        }
    }
}
