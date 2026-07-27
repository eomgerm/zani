package com.a105.zani.recording.application.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum RecordingWebhookErrorCode implements ErrorCode {
    INVALID_WEBHOOK_SIGNATURE(ErrorType.UNAUTHORIZED, "RECORDING_WEBHOOK_001", "Invalid LiveKit webhook signature"),
    RECORDING_NOT_READY(
            ErrorType.SERVICE_UNAVAILABLE,
            "RECORDING_WEBHOOK_002",
            "Recording row for the egress event is not visible yet"),
    WEBHOOK_EVENT_STORE_UNAVAILABLE(
            ErrorType.SERVICE_UNAVAILABLE, "RECORDING_WEBHOOK_003", "Recording webhook event store is unavailable");

    private final ErrorType type;
    private final String code;
    private final String message;

    RecordingWebhookErrorCode(ErrorType type, String code, String message) {
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
