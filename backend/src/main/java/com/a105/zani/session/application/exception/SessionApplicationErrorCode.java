package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum SessionApplicationErrorCode implements ErrorCode {
    ACTIVE_SESSION_EXISTS(ErrorType.CONFLICT, "SESSION_APP_001", "An active session already exists"),
    ACTIVATION_LOCK_UNAVAILABLE(
            ErrorType.SERVICE_UNAVAILABLE,
            "SESSION_APP_002",
            "Session activation lock store is unavailable"),
    DUPLICATE_INVITE_CODE(ErrorType.CONFLICT, "SESSION_APP_003", "Invite code already in use"),
    INVITE_CODE_GENERATION_FAILED(
            ErrorType.INTERNAL_SERVER_ERROR,
            "SESSION_APP_004",
            "Failed to generate a unique invite code");

    private final ErrorType type;
    private final String code;
    private final String message;

    SessionApplicationErrorCode(ErrorType type, String code, String message) {
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
