package com.a105.zani.postclass.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 원본 트랙 분할에 실패했다.
 *
 * <p>재시도 가능한 실패다 — 원본은 읽기 전용 마운트에 그대로 있고 임시 산출물만 버리면 된다. 재시도할 때 같은 원본을 다시 자르고, 저장해 둔 청크 구간과 새 CSV 를 대조해 경계가 어긋났는지 확인한다.
 */
public class AudioChunkFailedException extends BusinessException {

    public AudioChunkFailedException() {
        super(PostClassTranscriptionErrorCode.AUDIO_CHUNK_FAILED);
    }

    public AudioChunkFailedException(Throwable cause) {
        super(PostClassTranscriptionErrorCode.AUDIO_CHUNK_FAILED, cause);
    }
}
