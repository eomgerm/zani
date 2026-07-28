package com.a105.zani.attention.application.exception;

import com.a105.zani.common.error.BusinessException;

/** 학생이 아닌 참가자가 프롬프트에 답했다. 프롬프트는 학생에게만 뜬다. */
public class NotPromptStudentException extends BusinessException {

    public NotPromptStudentException() {
        super(PromptResponseErrorCode.NOT_SESSION_STUDENT);
    }
}
