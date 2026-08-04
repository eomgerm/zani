package com.a105.zani.postclass.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 전사 문서가 저장 계약을 어겼다(S15P11A105-247).
 *
 * <p><b>재시도하지 않는다.</b> {@link TranscriptStoreUnavailableException} 과 나누는 기준이 이것이다. 연결이 끊기거나 잠금 대기로 실패한 것은 잠시 뒤 같은 요청이
 * 성공할 수 있지만, 저장된 JSON 이 현재 형태와 다른 것은 몇 번을 읽어도 같다. 재시도로 분류하면 예산만 태우고 원인은 그대로 남는다.
 *
 * <p>쓰기에서도 이 예외가 나갈 수 있다. 직렬화할 값은 타입이 확정된 {@code TranscriptDocument} 이므로, 그것을 문자열로 만들지 못한다는 것은 저장소 상태가 아니라 코드·계약 문제다.
 *
 * <p>읽기에서 빈 값으로 돌려주지 않는 이유는 그 편이 더 위험하기 때문이다. "전사가 없다" 로 오인되면 재조립이 일어나 읽지 못한 옛 문서를 덮어쓴다 — 되돌릴 근거를 스스로 지우는 셈이다.
 */
public class TranscriptDocumentInvalidException extends BusinessException {

    public TranscriptDocumentInvalidException(Throwable cause) {
        super(PostClassTranscriptionErrorCode.TRANSCRIPT_DOCUMENT_INVALID, cause);
    }
}
