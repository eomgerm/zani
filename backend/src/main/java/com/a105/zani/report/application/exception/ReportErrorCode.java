package com.a105.zani.report.application.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum ReportErrorCode implements ErrorCode {
    NOT_SESSION_STUDENT(ErrorType.FORBIDDEN, "REPORT_001", "Only the session's own student can access this report"),
    REPORT_NOT_READY(ErrorType.NOT_FOUND, "REPORT_002", "The student report is not published yet");

    private final ErrorType type;
    private final String code;
    private final String message;

    ReportErrorCode(ErrorType type, String code, String message) {
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
