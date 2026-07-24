package com.a105.zani.auth.application.exception;

import com.a105.zani.common.error.BusinessException;

public class InvalidGoogleIdTokenException extends BusinessException {

    public InvalidGoogleIdTokenException() {
        super(AuthErrorCode.INVALID_GOOGLE_ID_TOKEN);
    }

    public InvalidGoogleIdTokenException(Throwable cause) {
        super(AuthErrorCode.INVALID_GOOGLE_ID_TOKEN, cause);
    }
}
