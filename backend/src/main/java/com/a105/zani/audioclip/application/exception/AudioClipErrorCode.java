package com.a105.zani.audioclip.application.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum AudioClipErrorCode implements ErrorCode {
    SESSION_NOT_FOUND(ErrorType.NOT_FOUND, "AUDIO_CLIP_001", "No session found for the given id"),
    SESSION_ALREADY_ENDED(ErrorType.CONFLICT, "AUDIO_CLIP_002", "Session has already ended"),
    NOT_SESSION_INSTRUCTOR(ErrorType.FORBIDDEN, "AUDIO_CLIP_003", "Only the session instructor can manage audio clips"),
    REQUEST_NOT_FOUND(ErrorType.NOT_FOUND, "AUDIO_CLIP_004", "No audio clip request found for the given id"),
    REQUEST_EXPIRED(ErrorType.CONFLICT, "AUDIO_CLIP_005", "Audio clip request has expired"),
    REQUEST_ALREADY_RESOLVED(ErrorType.CONFLICT, "AUDIO_CLIP_006", "Audio clip request was already resolved"),
    UNSUPPORTED_FORMAT(ErrorType.UNSUPPORTED_MEDIA_TYPE, "AUDIO_CLIP_007", "Only audio/webm clips are supported"),
    CLIP_TOO_LARGE(ErrorType.PAYLOAD_TOO_LARGE, "AUDIO_CLIP_008", "Audio clip exceeds the maximum allowed size"),
    INSUFFICIENT_DURATION(
            ErrorType.BAD_REQUEST, "AUDIO_CLIP_009", "Audio clip is shorter than the minimum uploadable duration"),
    STORE_UNAVAILABLE(ErrorType.SERVICE_UNAVAILABLE, "AUDIO_CLIP_010", "Audio clip request store is unavailable"),
    TRANSCRIPTION_FAILED(ErrorType.SERVICE_UNAVAILABLE, "AUDIO_CLIP_011", "Audio transcription failed");

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
