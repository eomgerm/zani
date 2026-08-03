package com.a105.zani.postclass.domain.model;

import java.time.Instant;

/**
 * 실패한 단계를 다시 시도할지에 대한 판단.
 *
 * @param nextAttemptAt 재시도할 시각. 포기하면 {@code null}
 * @param giveUpReason 포기 사유. 재시도하면 {@code null}
 */
public record RetryDecision(Instant nextAttemptAt, GiveUpReason giveUpReason) {

    /** 재시도를 포기하는 이유. 어느 쪽이든 결과는 FAILED 지만, 운영에서 원인을 가르려면 구분이 필요하다. */
    public enum GiveUpReason {
        /** 다시 시도해도 같은 결과인 실패다(스키마 위반·권한 없음 등). */
        NOT_RETRYABLE,
        /** 시도 횟수 상한을 썼다. */
        ATTEMPTS_EXHAUSTED,
        /** 다음 시도가 8시간 마감을 넘긴다. 늦게 성공한 결과는 요구를 만족하지 못한다(AI-006). */
        SLA_EXPIRED
    }

    public static RetryDecision retryAt(Instant nextAttemptAt) {
        return new RetryDecision(nextAttemptAt, null);
    }

    public static RetryDecision giveUp(GiveUpReason reason) {
        return new RetryDecision(null, reason);
    }

    public boolean shouldRetry() {
        return nextAttemptAt != null;
    }
}
