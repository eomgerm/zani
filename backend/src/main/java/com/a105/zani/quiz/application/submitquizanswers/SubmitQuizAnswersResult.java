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
                            question.explanation(),
                            question.sectionStartedOffsetMs());
                })
                .toList();
        int correctCount =
                (int) results.stream().filter(QuestionGrading::correct).count();
        return new SubmitQuizAnswersResult(snapshot.quizId(), results.size(), correctCount, results);
    }

    /**
     * 문항 하나의 채점.
     *
     * @param sectionStartedOffsetMs 문항이 가리키는 개념 구간의 시작 시각(ms). 학생이 그 구간을 다시 보게 만드는 링크의 재생 위치다(FRD §19.4 — 풀이 직후 정답과 해당
     *     개념의 녹화 구간). 근거 구간을 특정하지 못한 문항은 {@code null} 이고 그때는 링크를 만들지 않는다
     */
    public record QuestionGrading(
            Long questionId,
            boolean correct,
            Long selectedOptionId,
            Long correctOptionId,
            String explanation,
            Long sectionStartedOffsetMs) {}
}
