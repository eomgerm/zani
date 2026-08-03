package com.a105.zani.postclass.application.recordpipelinefailure;

import java.time.Instant;

import com.a105.zani.postclass.domain.model.PipelineStatus;
import com.a105.zani.postclass.domain.model.RetryDecision;

/**
 * 실패를 기록한 뒤의 작업 상태.
 *
 * @param status 이 호출이 끝난 시점의 단계. 재시도로 남았으면 실패한 단계 그대로, 접었으면 {@code FAILED}
 * @param nextAttemptAt 재시도할 시각. 접었으면 {@code null}
 * @param giveUpReason 접은 이유. 재시도로 남았으면 {@code null}
 */
public record RecordPipelineFailureResult(
        PipelineStatus status, Instant nextAttemptAt, RetryDecision.GiveUpReason giveUpReason) {

    public boolean willRetry() {
        return nextAttemptAt != null;
    }
}
