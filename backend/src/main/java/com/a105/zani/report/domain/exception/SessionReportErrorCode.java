package com.a105.zani.report.domain.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum SessionReportErrorCode implements ErrorCode {
    INVALID_SESSION_REPORT(ErrorType.BAD_REQUEST, "SESSION_REPORT_001", "Invalid session report"),
    INVALID_SESSION_SECTION(ErrorType.BAD_REQUEST, "SESSION_REPORT_002", "Invalid session section"),
    SESSION_ALREADY_ANALYZED(ErrorType.CONFLICT, "SESSION_REPORT_003", "Session report already exists");

    private final ErrorType type;
    private final String code;
    private final String message;

    SessionReportErrorCode(ErrorType type, String code, String message) {
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
