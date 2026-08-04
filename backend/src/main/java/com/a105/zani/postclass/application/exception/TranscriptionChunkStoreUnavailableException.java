package com.a105.zani.postclass.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 전사 청크 체크포인트 저장소를 읽거나 쓸 수 없다.
 *
 * <p>재시도 가능한 실패로 다룬다. 진행 상태를 잃은 것이 아니라 지금 접근하지 못한 것이므로, 다음 주기에 같은 자리에서 이어갈 수 있다.
 */
public class TranscriptionChunkStoreUnavailableException extends BusinessException {

    public TranscriptionChunkStoreUnavailableException(Throwable cause) {
        super(PostClassTranscriptionErrorCode.TRANSCRIPTION_CHUNK_STORE_UNAVAILABLE, cause);
    }
}
