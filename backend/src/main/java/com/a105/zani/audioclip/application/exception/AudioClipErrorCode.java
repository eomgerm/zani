package com.a105.zani.audioclip.application.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum AudioClipErrorCode implements ErrorCode {
    TRANSCRIPTION_FAILED(ErrorType.SERVICE_UNAVAILABLE, "AUDIO_CLIP_001", "Audio transcription failed");

    private final ErrorType type;
    private final String code;
    private final String message;

    AudioClipErrorCode(ErrorType type, String code, String message) {
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
