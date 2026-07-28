package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.BusinessException;

public class NotSessionInstructorException extends BusinessException {

    public NotSessionInstructorException() {
        super(SessionApplicationErrorCode.NOT_SESSION_INSTRUCTOR);
    }
}
