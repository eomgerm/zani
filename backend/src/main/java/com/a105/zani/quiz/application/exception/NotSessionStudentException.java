package com.a105.zani.quiz.application.exception;

import com.a105.zani.common.error.BusinessException;

/** 퀴즈는 세션에 참여한 학생 본인에게만 열린다. 비참여자와 강사는 모두 여기서 거절된다(FRD §21 권한표). */
public class NotSessionStudentException extends BusinessException {

    public NotSessionStudentException() {
        super(QuizErrorCode.NOT_SESSION_STUDENT);
    }
}
