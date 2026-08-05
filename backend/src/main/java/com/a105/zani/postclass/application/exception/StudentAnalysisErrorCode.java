package com.a105.zani.postclass.application.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum StudentAnalysisErrorCode implements ErrorCode {
    /** 서버 오류가 아니라 순서 문제다. 공통 분석이 끝난 뒤 다시 부르면 풀린다. */
    SESSION_ANALYSIS_CONTEXT_MISSING(
            ErrorType.CONFLICT,
            "POSTCLASS_ANALYSIS_001",
            "The session has no common analysis or concept section to analyze students against");

    private final ErrorType type;
    private final String code;
    private final String message;

    StudentAnalysisErrorCode(ErrorType type, String code, String message) {
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
