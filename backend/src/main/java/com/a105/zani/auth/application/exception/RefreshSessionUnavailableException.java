package com.a105.zani.auth.application.exception;

import com.a105.zani.common.error.BusinessException;

public class RefreshSessionUnavailableException extends BusinessException {

    public RefreshSessionUnavailableException(Throwable cause) {
        super(AuthErrorCode.REFRESH_SESSION_UNAVAILABLE, cause);
    }
}
