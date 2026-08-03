package com.a105.zani.postclass.domain.model;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostClassRetryPolicyTest {

    private static final Instant QUEUED_AT = Instant.parse("2026-07-30T09:00:00Z");
    /** 마감까지 넉넉히 남은 시각. 시간 예산이 판단에 끼어들지 않게 한다. */
    private static final Instant EARLY = QUEUED_AT.plus(Duration.ofMinutes(10));

    @Test
    void retriesARetryableFailureWithExponentialBackoff() {
        assertEquals(
                EARLY.plus(Duration.ofMinutes(2)),
                PostClassRetryPolicy.decide(1, QUEUED_AT, EARLY, true).nextAttemptAt());
        assertEquals(
                EARLY.plus(Duration.ofMinutes(4)),
                PostClassRetryPolicy.decide(2, QUEUED_AT, EARLY, true).nextAttemptAt());
        assertEquals(
                EARLY.plus(Duration.ofMinutes(8)),
                PostClassRetryPolicy.decide(3, QUEUED_AT, EARLY, true).nextAttemptAt());
        assertEquals(
                EARLY.plus(Duration.ofMinutes(16)),
                PostClassRetryPolicy.decide(4, QUEUED_AT, EARLY, true).nextAttemptAt());
    }

    @Test
    void doesNotRetryAFailureThatWouldRepeat() {
        // 스키마 위반처럼 몇 번을 보내도 같은 실패는 시도 횟수가 남아 있어도 접는다.
        RetryDecision decision = PostClassRetryPolicy.decide(1, QUEUED_AT, EARLY, false);

        assertFalse(decision.shouldRetry());
        assertEquals(RetryDecision.GiveUpReason.NOT_RETRYABLE, decision.giveUpReason());
    }

    @Test
    void givesUpWhenTheAttemptsAreExhausted() {
        RetryDecision decision = PostClassRetryPolicy.decide(5, QUEUED_AT, EARLY, true);

        assertFalse(decision.shouldRetry());
        assertEquals(RetryDecision.GiveUpReason.ATTEMPTS_EXHAUSTED, decision.giveUpReason());
    }

    @Test
    void givesUpWhenTheNextAttemptWouldLandAfterTheDeadline() {
        // 마감 1분 전. 다음 시도는 2분 뒤라 마감을 넘긴다 — 성공해도 8시간 요구를 만족하지 못한다(AI-006).
        Instant justBeforeDeadline = QUEUED_AT.plus(PostClassRetryPolicy.SLA).minus(Duration.ofMinutes(1));

        RetryDecision decision = PostClassRetryPolicy.decide(1, QUEUED_AT, justBeforeDeadline, true);

        assertFalse(decision.shouldRetry());
        assertEquals(RetryDecision.GiveUpReason.SLA_EXPIRED, decision.giveUpReason());
    }

    @Test
    void retriesWhenTheNextAttemptStillFitsBeforeTheDeadline() {
        // 마감 3분 전이면 2분 뒤 시도는 아직 마감 안이다.
        Instant threeMinutesLeft = QUEUED_AT.plus(PostClassRetryPolicy.SLA).minus(Duration.ofMinutes(3));

        RetryDecision decision = PostClassRetryPolicy.decide(1, QUEUED_AT, threeMinutesLeft, true);

        assertTrue(decision.shouldRetry());
    }

    @Test
    void countsTheDeadlineAsEightHoursAfterTheNoteWasFinalized() {
        assertEquals(QUEUED_AT.plus(Duration.ofHours(8)), PostClassRetryPolicy.deadline(QUEUED_AT));
    }

    @Test
    void reportsAJobAsOverdueOnlyOnceTheDeadlineHasPassed() {
        Instant deadline = PostClassRetryPolicy.deadline(QUEUED_AT);

        assertFalse(PostClassRetryPolicy.isOverdue(QUEUED_AT, deadline.minusSeconds(1)));
        // 마감 정각도 넘긴 것으로 본다 — 8시간 "안에" 끝나야 하므로 정각에는 이미 늦었다.
        assertTrue(PostClassRetryPolicy.isOverdue(QUEUED_AT, deadline));
        assertTrue(PostClassRetryPolicy.isOverdue(QUEUED_AT, deadline.plusSeconds(1)));
    }
}
