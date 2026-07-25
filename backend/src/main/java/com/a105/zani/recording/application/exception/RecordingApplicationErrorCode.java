package com.a105.zani.recording.application.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum RecordingApplicationErrorCode implements ErrorCode {
    TRACK_EGRESS_UNAVAILABLE(ErrorType.SERVICE_UNAVAILABLE, "RECORDING_APP_001", "LiveKit track egress is unavailable"),
    RECORDING_OUTBOX_UNAVAILABLE(
            ErrorType.SERVICE_UNAVAILABLE, "RECORDING_APP_002", "Recording outbox store is unavailable");

    private final ErrorType type;
    private final String code;
    private final String message;

    RecordingApplicationErrorCode(ErrorType type, String code, String message) {
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
