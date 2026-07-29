package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.BusinessException;

public class SessionNotJoinableException extends BusinessException {

    public SessionNotJoinableException() {
        super(SessionApplicationErrorCode.SESSION_NOT_JOINABLE);
    }
}
