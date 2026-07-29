package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.BusinessException;

public class SessionNotStartedException extends BusinessException {

    public SessionNotStartedException() {
        super(SessionApplicationErrorCode.SESSION_NOT_STARTED);
    }
}
