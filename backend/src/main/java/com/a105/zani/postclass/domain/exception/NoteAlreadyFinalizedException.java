package com.a105.zani.postclass.domain.exception;

import com.a105.zani.common.error.BusinessException;

/** 확정된 메모는 수정·재생성할 수 없다(FRD §16). 중복 <b>확정</b>은 오류가 아니라 멱등 성공이므로 여기에 해당하지 않는다. */
public class NoteAlreadyFinalizedException extends BusinessException {

    public NoteAlreadyFinalizedException() {
        super(PostClassNoteErrorCode.NOTE_ALREADY_FINALIZED);
    }
}
