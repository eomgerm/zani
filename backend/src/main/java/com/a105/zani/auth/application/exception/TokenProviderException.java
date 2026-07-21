package com.a105.zani.auth.application.exception;

import com.a105.zani.common.error.BusinessException;

public class TokenProviderException extends BusinessException {

    public TokenProviderException(Throwable cause) {
        super(AuthErrorCode.TOKEN_PROVIDER_FAILURE, cause);
    }
}
