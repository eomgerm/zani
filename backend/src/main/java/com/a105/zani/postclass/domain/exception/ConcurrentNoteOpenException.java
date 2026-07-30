package com.a105.zani.postclass.domain.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 같은 세션의 메모를 다른 요청이 먼저 열었다.
 *
 * <p>세션당 메모는 한 행이라(UK_INSTRUCTOR_NOTES_SESSION), 강사가 창을 두 개 열어 두고 첫 자동 저장이 동시에 나가면 한쪽이 유니크 제약에 걸린다. 다음 자동 저장은 이미 만들어진
 * 행에 얹히므로 클라이언트는 그대로 재시도하면 된다.
 */
public class ConcurrentNoteOpenException extends BusinessException {

    public ConcurrentNoteOpenException() {
        super(PostClassNoteErrorCode.CONCURRENT_NOTE_OPEN);
    }
}
