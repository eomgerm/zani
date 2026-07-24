package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.BusinessException;

public class SessionAlreadyEndedException extends BusinessException {

    public SessionAlreadyEndedException() {
        super(MediaTokenErrorCode.SESSION_ALREADY_ENDED);
    }
}
