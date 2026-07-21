package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.BusinessException;

public class SessionLockUnavailableException extends BusinessException {

    public SessionLockUnavailableException(Throwable cause) {
        super(SessionApplicationErrorCode.ACTIVATION_LOCK_UNAVAILABLE, cause);
    }
}
