package com.a105.zani.quiz.application.exception;

import com.a105.zani.common.error.BusinessException;

/** 부분 답안, 중복 문항, 퀴즈에 없는 문항, 또는 그 문항의 것이 아닌 보기 선택. 제출은 전 문항 일괄이어야 한다. */
public class InvalidQuizAnswersException extends BusinessException {

    public InvalidQuizAnswersException() {
        super(QuizErrorCode.INVALID_QUIZ_ANSWERS);
    }
}
