package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.BusinessException;

public class ActiveSessionExistsException extends BusinessException {

    public ActiveSessionExistsException() {
        super(SessionApplicationErrorCode.ACTIVE_SESSION_EXISTS);
    }
}
