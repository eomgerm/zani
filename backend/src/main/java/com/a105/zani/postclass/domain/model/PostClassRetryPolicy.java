package com.a105.zani.postclass.domain.model;

import java.time.Duration;
import java.time.Instant;

/**
 * 사후 파이프라인 단계가 실패했을 때 다시 시도할지 정한다.
 *
 * <p><b>재시도 상한은 횟수만이 아니라 8시간 예산이다.</b> 메모 확정 후 8시간 안에 전체 결과가 나와야 하므로(AI-006), 다음 시도가 마감을 넘긴다면 그 시도는 성공해도 요구를 만족하지 못한다.
 * 횟수만 세면 백오프가 벌어질수록 마감을 한참 넘긴 재시도를 계속하게 되고, 그동안 작업은 실패로 확정되지 않아 아무도 손쓰지 못한다. 그래서 마감을 넘길 시도는 하지 않고 바로 접는다.
 *
 * <p>어떤 실패가 재시도할 만한지는 단계마다 다르고, 그 판단은 단계를 수행하는 쪽이 한다 — GMS 호출 한도 초과는 기다리면 풀리지만 구조화 스키마 위반은 몇 번을 보내도 같다. 이 정책은 그 판단을
 * {@code retryable} 로 받아 "언제 다시 할지"와 "언제 접을지"만 정한다.
 */
public final class PostClassRetryPolicy {

    /** 메모 확정부터 전체 결과까지 주어진 시간(FRD AI-006, NFR-PERF-004). */
    public static final Duration SLA = Duration.ofHours(8);

    /** 한 단계에 허용하는 시도 횟수. 백오프를 모두 쓰면 약 32분이라 8시간 예산에서 차지하는 몫이 크지 않다 — 단계가 다섯이므로 한 단계가 예산을 독차지하면 안 된다. */
    private static final int MAX_ATTEMPTS = 5;

    /** 백오프 기본 간격. 시도가 쌓일수록 2배씩 늘어난다(2m, 4m, 8m, 16m). */
    private static final Duration BASE_DELAY = Duration.ofMinutes(2);

    private PostClassRetryPolicy() {}

    /**
     * @param attemptCount 이번 실패까지 포함한 현재 단계의 시도 횟수
     * @param queuedAt 메모가 확정되어 작업이 등록된 시각. 8시간 마감의 기준점이다
     * @param retryable 다시 시도하면 결과가 달라질 수 있는 실패인지
     */
    public static RetryDecision decide(int attemptCount, Instant queuedAt, Instant now, boolean retryable) {
        if (!retryable) {
            return RetryDecision.giveUp(RetryDecision.GiveUpReason.NOT_RETRYABLE);
        }
        if (attemptCount >= MAX_ATTEMPTS) {
            return RetryDecision.giveUp(RetryDecision.GiveUpReason.ATTEMPTS_EXHAUSTED);
        }
        Instant nextAttemptAt = now.plus(backoff(attemptCount));
        if (!nextAttemptAt.isBefore(deadline(queuedAt))) {
            return RetryDecision.giveUp(RetryDecision.GiveUpReason.SLA_EXPIRED);
        }
        return RetryDecision.retryAt(nextAttemptAt);
    }

    /** 이 작업이 끝나야 하는 시각. */
    public static Instant deadline(Instant queuedAt) {
        return queuedAt.plus(SLA);
    }

    /** 마감을 이미 넘겼는지. 경보가 이 값으로 대상을 고른다. */
    public static boolean isOverdue(Instant queuedAt, Instant now) {
        return !now.isBefore(deadline(queuedAt));
    }

    /** 지수 백오프: 2m, 4m, 8m, 16m. */
    private static Duration backoff(int attemptCount) {
        return BASE_DELAY.multipliedBy(1L << Math.min(Math.max(attemptCount - 1, 0), MAX_ATTEMPTS - 2));
    }
}
