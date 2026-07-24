package com.a105.zani.member.application.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum MemberApplicationErrorCode implements ErrorCode {
    DUPLICATE_GOOGLE_SUBJECT(
            ErrorType.CONFLICT, "MEMBER_APP_001", "A member already exists for this Google account");

    private final ErrorType type;
    private final String code;
    private final String message;

    MemberApplicationErrorCode(ErrorType type, String code, String message) {
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
