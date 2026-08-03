package com.a105.zani.postclass.application.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum PostClassJobErrorCode implements ErrorCode {
    PIPELINE_JOB_UNAVAILABLE(
            ErrorType.SERVICE_UNAVAILABLE, "POSTCLASS_JOB_001", "Post-class pipeline job store is unavailable");

    private final ErrorType type;
    private final String code;
    private final String message;

    PostClassJobErrorCode(ErrorType type, String code, String message) {
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
