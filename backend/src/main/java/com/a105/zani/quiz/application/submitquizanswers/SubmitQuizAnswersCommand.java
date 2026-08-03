package com.a105.zani.quiz.application.submitquizanswers;

import java.util.List;

/** 답안 일괄 제출 입력. 퀴즈의 모든 문항이 정확히 한 번씩 담겨야 한다 — 부분 답안은 거절된다. */
public record SubmitQuizAnswersCommand(Long sessionId, Long memberId, List<QuizAnswerSelection> answers) {

    /** 문항 하나에 대한 선택. */
    public record QuizAnswerSelection(Long questionId, Long selectedOptionId) {}
}
