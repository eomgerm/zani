package com.a105.zani.auth.application.exception;

import com.a105.zani.common.error.BusinessException;

public class InvalidRefreshTokenException extends BusinessException {

    public InvalidRefreshTokenException() {
        super(AuthErrorCode.INVALID_REFRESH_TOKEN);
    }

    public InvalidRefreshTokenException(Throwable cause) {
        super(AuthErrorCode.INVALID_REFRESH_TOKEN, cause);
    }
}
