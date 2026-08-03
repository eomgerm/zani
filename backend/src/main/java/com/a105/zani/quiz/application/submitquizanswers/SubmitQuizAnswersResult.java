package com.a105.zani.quiz.application.submitquizanswers;

import java.util.List;
import java.util.Map;

import com.a105.zani.quiz.application.port.StudentQuizSnapshot;

/** 제출 직후의 채점 결과 — 문항별 정오·내 선택·정답·해설과 전체 요약. 제출 후 GET 조회와 같은 정보를 준다. */
public record SubmitQuizAnswersResult(Long quizId, int totalCount, int correctCount, List<QuestionGrading> results) {

    /** 저장 전 스냅샷과 이번 제출 선택으로 채점한다 — 답안 재조회 없이 같은 트랜잭션 안의 사실만 쓴다. */
    public static SubmitQuizAnswersResult grade(StudentQuizSnapshot snapshot, Map<Long, Long> selectionsByQuestion) {
        List<QuestionGrading> results = snapshot.questions().stream()
                .map(question -> {
                    Long selected = selectionsByQuestion.get(question.questionId());
                    return new QuestionGrading(
                            question.questionId(),
                            question.isCorrectOption(selected),
                            selected,
                            question.correctOptionId(),
                            question.explanation());
                })
                .toList();
        int correctCount =
                (int) results.stream().filter(QuestionGrading::correct).count();
        return new SubmitQuizAnswersResult(snapshot.quizId(), results.size(), correctCount, results);
    }

    /** 문항 하나의 채점. */
    public record QuestionGrading(
            Long questionId, boolean correct, Long selectedOptionId, Long correctOptionId, String explanation) {}
}
