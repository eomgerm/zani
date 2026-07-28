package com.a105.zani.attention.application.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum PromptResponseErrorCode implements ErrorCode {
    NOT_SESSION_STUDENT(ErrorType.FORBIDDEN, "PROMPT_RESPONSE_001", "Only session students can answer check prompts"),
    MISMATCHED_ANSWER(ErrorType.BAD_REQUEST, "PROMPT_RESPONSE_002", "The answer does not belong to this prompt kind"),
    INVALID_PROMPT_TIMELINE(
            ErrorType.BAD_REQUEST,
            "PROMPT_RESPONSE_003",
            "The prompt timeline is inconsistent with the session timeline"),
    STALE_PROMPT(ErrorType.CONFLICT, "PROMPT_RESPONSE_004", "The prompt is too old to be answered");

    private final ErrorType type;
    private final String code;
    private final String message;

    PromptResponseErrorCode(ErrorType type, String code, String message) {
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
