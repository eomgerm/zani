package com.a105.zani.quiz.application.exception;

import com.a105.zani.common.error.BusinessException;

/** 답안은 1회 제출로 확정된다(2026-07-31 회의). 재응시가 없으므로 두 번째 제출은 거절된다. */
public class QuizAlreadySubmittedException extends BusinessException {

    public QuizAlreadySubmittedException() {
        super(QuizErrorCode.QUIZ_ALREADY_SUBMITTED);
    }

    public QuizAlreadySubmittedException(Throwable cause) {
        super(QuizErrorCode.QUIZ_ALREADY_SUBMITTED, cause);
    }
}
