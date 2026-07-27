package com.a105.zani.audioclip.application.exception;

import com.a105.zani.common.error.BusinessException;

public class NotSessionInstructorException extends BusinessException {

    public NotSessionInstructorException() {
        super(AudioClipErrorCode.NOT_SESSION_INSTRUCTOR);
    }
}
