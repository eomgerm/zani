package com.a105.zani.postclass.domain.exception;

import com.a105.zani.common.error.BusinessException;

/** 메모 본문이 허용 길이를 넘었다. 빈 본문은 유효하다 — 메모 없이 완료하는 경로가 있다(FRD §16). */
public class InvalidNoteContentException extends BusinessException {

    public InvalidNoteContentException() {
        super(PostClassNoteErrorCode.INVALID_NOTE_CONTENT);
    }
}
