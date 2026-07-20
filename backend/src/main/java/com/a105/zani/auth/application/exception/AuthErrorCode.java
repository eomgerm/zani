package com.a105.zani.auth.application.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum AuthErrorCode implements ErrorCode {
    MISSING_REFRESH_TOKEN(ErrorType.UNAUTHORIZED, "AUTH_001", "Refresh token is missing"),
    INVALID_REFRESH_TOKEN(ErrorType.UNAUTHORIZED, "AUTH_002", "Refresh token is invalid"),
    REUSED_REFRESH_TOKEN(
            ErrorType.UNAUTHORIZED,
            "AUTH_003",
            "Refresh token has already been used or revoked"),
    REFRESH_ORIGIN_FORBIDDEN(
            ErrorType.FORBIDDEN,
            "AUTH_004",
            "Refresh request origin is not allowed"),
    TOKEN_PROVIDER_FAILURE(
            ErrorType.INTERNAL_SERVER_ERROR,
            "AUTH_005",
            "Token processing failed"),
    REFRESH_SESSION_UNAVAILABLE(
            ErrorType.SERVICE_UNAVAILABLE,
            "AUTH_006",
            "Refresh session store is unavailable");

    private final ErrorType type;
    private final String code;
    private final String message;

    AuthErrorCode(ErrorType type, String code, String message) {
        this.type = type;
        this.code = code;
        this.message = message;
    }

    @Override
    public ErrorType type() {
        return type;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
