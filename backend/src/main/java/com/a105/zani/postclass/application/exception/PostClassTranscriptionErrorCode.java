package com.a105.zani.postclass.application.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

/** 사후 전사 실패 사유(S15P11A105-247). 내부 job 경로라 HTTP 로 나가지 않지만, 재시도 분류에 쓰인다. */
public enum PostClassTranscriptionErrorCode implements ErrorCode {
    AUDIO_CHUNK_FAILED(
            ErrorType.SERVICE_UNAVAILABLE,
            "POSTCLASS_TRANSCRIPTION_001",
            "Failed to split the recorded track into upload-sized chunks"),
    TRANSCRIPTION_CHUNK_STORE_UNAVAILABLE(
            ErrorType.SERVICE_UNAVAILABLE,
            "POSTCLASS_TRANSCRIPTION_002",
            "Post-class transcription chunk store is unavailable"),
    TRANSCRIPT_STORE_UNAVAILABLE(
            ErrorType.SERVICE_UNAVAILABLE, "POSTCLASS_TRANSCRIPTION_003", "Transcript store is unavailable"),
    TRANSCRIPTION_CALL_FAILED(
            ErrorType.SERVICE_UNAVAILABLE, "POSTCLASS_TRANSCRIPTION_004", "GMS transcription call failed"),
    CHUNK_BOUNDARY_MISMATCH(
            ErrorType.CONFLICT,
            "POSTCLASS_TRANSCRIPTION_005",
            "Re-split chunk boundaries disagree with the recorded checkpoints");

    private final ErrorType type;
    private final String code;
    private final String message;

    PostClassTranscriptionErrorCode(ErrorType type, String code, String message) {
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
