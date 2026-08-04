package com.a105.zani.postclass.application.exception;

import com.a105.zani.common.error.BusinessException;

/** {@code transcripts} 에 읽거나 쓸 수 없다(S15P11A105-247). 재시도 가능한 실패다. */
public class TranscriptStoreUnavailableException extends BusinessException {

    public TranscriptStoreUnavailableException(Throwable cause) {
        super(PostClassTranscriptionErrorCode.TRANSCRIPT_STORE_UNAVAILABLE, cause);
    }
}
