package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum SessionPresenceErrorCode implements ErrorCode {
    PRESENCE_STORE_UNAVAILABLE(
            ErrorType.SERVICE_UNAVAILABLE, "SESSION_PRESENCE_001", "Session presence store is unavailable");

    private final ErrorType type;
    private final String code;
    private final String message;

    SessionPresenceErrorCode(ErrorType type, String code, String message) {
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
