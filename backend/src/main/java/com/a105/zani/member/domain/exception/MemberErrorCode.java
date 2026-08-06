package com.a105.zani.member.domain.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum MemberErrorCode implements ErrorCode {
    INVALID_GOOGLE_IDENTITY(ErrorType.BAD_REQUEST, "MEMBER_001", "Google identity is missing required fields"),
    INVALID_DISPLAY_NAME(ErrorType.BAD_REQUEST, "MEMBER_002", "Display name must be 1 to 100 characters");

    private final ErrorType type;
    private final String code;
    private final String message;

    MemberErrorCode(ErrorType type, String code, String message) {
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
