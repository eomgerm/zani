package com.a105.zani.session.domain.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum SessionErrorCode implements ErrorCode {
    INVALID_TITLE(ErrorType.BAD_REQUEST, "SESSION_001", "Session title is invalid"),
    INVALID_INVITE_CODE(ErrorType.BAD_REQUEST, "SESSION_002", "Invite code format is invalid"),
    ILLEGAL_STATUS_TRANSITION(ErrorType.CONFLICT, "SESSION_003", "Session cannot move to the requested status");

    private final ErrorType type;
    private final String code;
    private final String message;

    SessionErrorCode(ErrorType type, String code, String message) {
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
