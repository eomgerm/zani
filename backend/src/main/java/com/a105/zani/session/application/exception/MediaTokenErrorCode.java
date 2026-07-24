package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum MediaTokenErrorCode implements ErrorCode {
    SESSION_NOT_FOUND(ErrorType.NOT_FOUND, "MEDIA_TOKEN_001", "No session found for the given id"),
    NOT_SESSION_MEMBER(ErrorType.FORBIDDEN, "MEDIA_TOKEN_002", "Not a participant of this session"),
    SESSION_ALREADY_ENDED(ErrorType.CONFLICT, "MEDIA_TOKEN_003", "Session has already ended"),
    LIVEKIT_NOT_CONFIGURED(ErrorType.SERVICE_UNAVAILABLE, "MEDIA_TOKEN_004", "LiveKit media server is not configured");

    private final ErrorType type;
    private final String code;
    private final String message;

    MediaTokenErrorCode(ErrorType type, String code, String message) {
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
