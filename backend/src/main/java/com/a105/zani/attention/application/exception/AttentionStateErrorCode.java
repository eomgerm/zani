package com.a105.zani.attention.application.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum AttentionStateErrorCode implements ErrorCode {
    ATTENTION_STORE_UNAVAILABLE(
            ErrorType.SERVICE_UNAVAILABLE, "ATTENTION_STATE_001", "Attention state store is unavailable");

    private final ErrorType type;
    private final String code;
    private final String message;

    AttentionStateErrorCode(ErrorType type, String code, String message) {
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
