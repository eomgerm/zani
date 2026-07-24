package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.BusinessException;

public class SessionNotFoundException extends BusinessException {

    public SessionNotFoundException() {
        super(SessionApplicationErrorCode.SESSION_NOT_FOUND);
    }
}
