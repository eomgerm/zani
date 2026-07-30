package com.a105.zani.postclass.domain.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum PostClassNoteErrorCode implements ErrorCode {
    INVALID_NOTE_CONTENT(ErrorType.BAD_REQUEST, "POSTCLASS_NOTE_001", "The note content exceeds the allowed length"),
    NOTE_ALREADY_FINALIZED(
            ErrorType.CONFLICT, "POSTCLASS_NOTE_002", "The note is already finalized and can no longer be edited"),
    CONCURRENT_NOTE_OPEN(
            ErrorType.CONFLICT, "POSTCLASS_NOTE_003", "Another request opened the note for this session first");

    private final ErrorType type;
    private final String code;
    private final String message;

    PostClassNoteErrorCode(ErrorType type, String code, String message) {
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
