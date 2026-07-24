package com.a105.zani.member.domain.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum MemberErrorCode implements ErrorCode {
    INVALID_GOOGLE_IDENTITY(ErrorType.BAD_REQUEST, "MEMBER_001", "Google identity is missing required fields");

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
