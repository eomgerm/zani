package com.a105.zani.quiz.application.port;

import java.util.List;

/** 학생 한 명의 퀴즈 전체 — 문항·보기·기존 답안. 문항은 출제 순서대로다. */
public record StudentQuizSnapshot(
        Long quizId,
        String title,
        String description,
        Short estimatedDurationMinutes,
        List<QuizQuestionSnapshot> questions) {

    /** 제출은 전 문항 일괄·1회라(부분 저장 없음) 답이 하나라도 있으면 제출 완료다. */
    public boolean submitted() {
        return questions.stream().anyMatch(question -> question.selectedOptionId() != null);
    }
}
