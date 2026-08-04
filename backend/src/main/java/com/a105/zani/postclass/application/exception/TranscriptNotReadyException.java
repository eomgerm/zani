package com.a105.zani.postclass.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 아직 종결되지 않은 청크가 있어 조립할 수 없다(S15P11A105-247).
 *
 * <p><b>재시도 가능한 실패다.</b> {@link TranscriptIncompleteException} 과 나누는 이유가 여기다. 둘 다 지금 {@code ANALYZING} 으로 넘기지 않지만 이후가
 * 다르다.
 *
 * <pre>
 * 이 예외        → 다음 시도까지 기다린다. 파이프라인을 최종 실패로 만들지 않는다
 * Incomplete    → 더 재시도하지 않는다. 파이프라인 전체를 실패로 종료한다
 * </pre>
 *
 * <p>합쳐 두면 오케스트레이션이 {@code retryable} 을 정할 근거를 잃는다. 그러면 두 방향으로 틀린다 — 영구 실패를 상한까지 재시도해 예산을 태우거나, 아직 처리 중인 세션을 너무 일찍 최종
 * 실패로 굳혀 남은 청크의 결과를 버린다.
 *
 * <p>{@code PENDING}·{@code PROCESSING} 청크가 남았는데 조립이 불렸다는 것은 보통 오케스트레이션이 종결을 기다리지 않고 넘어간 것이다. 그 자체가 버그일 수 있으므로 로그를
 * {@code ERROR} 로 남긴다 — 다만 데이터는 멀쩡하므로 실패 분류는 재시도 가능이다.
 */
public class TranscriptNotReadyException extends BusinessException {

    public TranscriptNotReadyException() {
        super(PostClassTranscriptionErrorCode.TRANSCRIPT_NOT_READY);
    }
}
