package com.a105.zani.report.domain.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum InstructorReportErrorCode implements ErrorCode {
    INVALID_INSTRUCTOR_REPORT(
            ErrorType.BAD_REQUEST, "REPORT_INSTRUCTOR_001", "The instructor report violates its own rules"),
    INVALID_CLASS_INSIGHT(
            ErrorType.BAD_REQUEST, "REPORT_INSTRUCTOR_002", "The class insight is not a usable feedback card"),
    INVALID_EVALUATION_TYPE(ErrorType.BAD_REQUEST, "REPORT_INSTRUCTOR_003", "The evaluation type is not defined");

    private final ErrorType type;
    private final String code;
    private final String message;

    InstructorReportErrorCode(ErrorType type, String code, String message) {
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
