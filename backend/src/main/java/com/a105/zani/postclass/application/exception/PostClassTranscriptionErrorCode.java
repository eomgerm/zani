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
            "Re-split chunk boundaries disagree with the recorded checkpoints"),
    TRANSCRIPT_INCOMPLETE(
            ErrorType.CONFLICT,
            "POSTCLASS_TRANSCRIPTION_006",
            "Cannot assemble a transcript while chunks are unfinished or failed"),
    TRANSCRIPT_ASSEMBLY_INVALID(
            ErrorType.INTERNAL_SERVER_ERROR,
            "POSTCLASS_TRANSCRIPTION_007",
            "Chunk results cannot be placed on the lesson timeline"),
    TRANSCRIPT_DOCUMENT_INVALID(
            ErrorType.INTERNAL_SERVER_ERROR,
            "POSTCLASS_TRANSCRIPTION_008",
            "Transcript document violates the stored document contract"),
    TRANSCRIPT_NOT_READY(
            ErrorType.CONFLICT, "POSTCLASS_TRANSCRIPTION_009", "Some transcription chunks have not finished yet"),
    TRANSCRIPTION_SOURCE_INVALID(
            ErrorType.INTERNAL_SERVER_ERROR,
            "POSTCLASS_TRANSCRIPTION_010",
            "Recorded track path cannot be resolved under the source root"),
    SESSION_RECORDING_NOT_SETTLED(
            ErrorType.CONFLICT,
            "POSTCLASS_TRANSCRIPTION_011",
            "Session recordings have not settled yet, more track files may arrive"),
    SESSION_RECORDING_BROKEN(
            ErrorType.CONFLICT,
            "POSTCLASS_TRANSCRIPTION_012",
            "Session recordings did not complete, some speech was never captured");

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
