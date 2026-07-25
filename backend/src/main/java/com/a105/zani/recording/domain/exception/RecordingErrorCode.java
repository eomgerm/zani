package com.a105.zani.recording.domain.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum RecordingErrorCode implements ErrorCode {
    INVALID_RECORDING_TRACK(ErrorType.BAD_REQUEST, "RECORDING_001", "Invalid recording track entry"),
    FORBIDDEN_STUDENT_CAMERA_TRACK(
            ErrorType.BAD_REQUEST, "RECORDING_002", "Student camera track must never be recorded"),
    INVALID_RECORDING_ALIAS(ErrorType.BAD_REQUEST, "RECORDING_003", "Invalid recording alias"),
    INVALID_RECORDING_MANIFEST(ErrorType.BAD_REQUEST, "RECORDING_004", "Invalid recording manifest");

    private final ErrorType type;
    private final String code;
    private final String message;

    RecordingErrorCode(ErrorType type, String code, String message) {
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
