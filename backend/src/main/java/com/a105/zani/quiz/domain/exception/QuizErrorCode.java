package com.a105.zani.quiz.domain.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum QuizErrorCode implements ErrorCode {
    INVALID_QUIZ(ErrorType.BAD_REQUEST, "QUIZ_001", "The quiz does not have three to five numbered questions"),
    INVALID_QUIZ_QUESTION(
            ErrorType.BAD_REQUEST, "QUIZ_002", "The quiz question does not have four options with exactly one answer");

    private final ErrorType type;
    private final String code;
    private final String message;

    QuizErrorCode(ErrorType type, String code, String message) {
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
