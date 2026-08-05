package com.a105.zani.quiz.domain.exception;

import com.a105.zani.common.error.BusinessException;

/** 퀴즈 구조가 응시·채점이 전제하는 형태를 벗어났다. 부분 저장은 없다 — 이 예외가 뜨면 그 학생의 분석은 실패로 끝난다. */
public class InvalidQuizException extends BusinessException {

    public InvalidQuizException(QuizErrorCode errorCode) {
        super(errorCode);
    }
}
