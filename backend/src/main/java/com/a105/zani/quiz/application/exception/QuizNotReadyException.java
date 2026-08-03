package com.a105.zani.quiz.application.exception;

import com.a105.zani.common.error.BusinessException;

/** 이 세션·학생의 퀴즈가 아직 없다. 수업이 진행 중이거나, 종료 후 LLM 분석 파이프라인이 끝나지 않은 상태다. */
public class QuizNotReadyException extends BusinessException {

    public QuizNotReadyException() {
        super(QuizErrorCode.QUIZ_NOT_READY);
    }
}
