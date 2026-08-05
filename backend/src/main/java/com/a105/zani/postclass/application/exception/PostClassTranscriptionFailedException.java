package com.a105.zani.postclass.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 청크 하나의 GMS 전사가 실패했다.
 *
 * <p>재시도 가능 여부는 {@link #retryable()} 가 알려준다. 이 구분이 필요한 이유는 재시도해도 결과가 같은 실패가 있기 때문이다 — 자격증명 오류나 응답 계약 위반은 몇 번을 다시 보내도 같은
 * 응답이 오므로 8시간 예산만 태운다. 반대로 timeout·429·5xx 는 다음 시도에 성공할 수 있다.
 *
 * <p>413 은 재시도 불가로 다룬다. 크기 가드가 있는데도 한도를 넘겼다는 뜻이라 같은 청크를 다시 보내도 같은 결과다 — 분할 쪽 결함이므로 재시도가 아니라 사람이 봐야 한다.
 */
public class PostClassTranscriptionFailedException extends BusinessException {

    private final boolean retryable;

    public PostClassTranscriptionFailedException(boolean retryable) {
        super(PostClassTranscriptionErrorCode.TRANSCRIPTION_CALL_FAILED);
        this.retryable = retryable;
    }

    public PostClassTranscriptionFailedException(boolean retryable, Throwable cause) {
        super(PostClassTranscriptionErrorCode.TRANSCRIPTION_CALL_FAILED, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
