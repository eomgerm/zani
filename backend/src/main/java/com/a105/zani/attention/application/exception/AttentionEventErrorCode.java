package com.a105.zani.attention.application.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum AttentionEventErrorCode implements ErrorCode {
    NOT_SESSION_STUDENT(
            ErrorType.FORBIDDEN, "ATTENTION_EVENT_001", "Only session students can report attention judgements"),
    INVALID_DETECTION_TIMELINE(
            ErrorType.BAD_REQUEST,
            "ATTENTION_EVENT_002",
            "The observation timeline is inconsistent with the session timeline"),
    UNSUPPORTED_DETECTOR_CONTRACT(
            ErrorType.BAD_REQUEST, "ATTENTION_EVENT_003", "The detector contract version is not supported");

    private final ErrorType type;
    private final String code;
    private final String message;

    AttentionEventErrorCode(ErrorType type, String code, String message) {
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
