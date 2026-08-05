package com.a105.zani.report.domain.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum StudentReportErrorCode implements ErrorCode {
    INVALID_STUDENT_REPORT(ErrorType.BAD_REQUEST, "REPORT_STUDENT_001", "The student report violates its own rules"),
    INVALID_REVIEW_RECOMMENDATION(
            ErrorType.BAD_REQUEST, "REPORT_STUDENT_002", "The review recommendation is not a usable review range"),
    INVALID_RECOMMENDATION_TYPE(ErrorType.BAD_REQUEST, "REPORT_STUDENT_003", "The recommendation type is not defined");

    private final ErrorType type;
    private final String code;
    private final String message;

    StudentReportErrorCode(ErrorType type, String code, String message) {
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
