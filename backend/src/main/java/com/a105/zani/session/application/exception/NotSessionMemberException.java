package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.BusinessException;

public class NotSessionMemberException extends BusinessException {

    public NotSessionMemberException() {
        super(MediaTokenErrorCode.NOT_SESSION_MEMBER);
    }
}
