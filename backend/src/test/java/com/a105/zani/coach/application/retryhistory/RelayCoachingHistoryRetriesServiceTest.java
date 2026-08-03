package com.a105.zani.coach.application.retryhistory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.a105.zani.attention.application.port.CoachingTip;
import com.a105.zani.attention.application.port.CoachingTipType;
import com.a105.zani.coach.application.port.CoachingHistoryRetryQueuePort;
import com.a105.zani.coach.application.storehistory.CoachingHistory;
import com.a105.zani.coach.application.storehistory.CoachingResponseCounts;
import com.a105.zani.coach.application.storehistory.CoachingTranscript;

import static org.assertj.core.api.Assertions.assertThat;

class RelayCoachingHistoryRetriesServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-31T01:00:00Z");

    @Test
    void acknowledgesAHistoryAfterIdempotentPersistenceSucceeds() {
        FakeRetryQueue queue = new FakeRetryQueue(history());
        RelayCoachingHistoryRetriesService service =
                new RelayCoachingHistoryRetriesService(queue, ignored -> false, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.relay()).isEqualTo(1);
        assertThat(queue.acknowledged).isTrue();
        assertThat(queue.rescheduledAt).isNull();
    }

    @Test
    void reschedulesWithoutDroppingAHistoryWhenMysqlIsStillUnavailable() {
        FakeRetryQueue queue = new FakeRetryQueue(history());
        RelayCoachingHistoryRetriesService service = new RelayCoachingHistoryRetriesService(
                queue,
                ignored -> {
                    throw new IllegalStateException("mysql down");
                },
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.relay()).isZero();
        assertThat(queue.acknowledged).isFalse();
        assertThat(queue.rescheduledAt).isAfter(NOW);
    }

    private CoachingHistory history() {
        return new CoachingHistory(
                7L,
                "trigger-1",
                NOW.minusSeconds(1),
                NOW,
                new CoachingResponseCounts(10, 4, 3, 1, 0, 0),
                CoachingTipType.CONFUSED,
                CoachingTranscript.transcribed(NOW.minusSeconds(30).toEpochMilli(), NOW.toEpochMilli()),
                "binary tree",
                new CoachingTip(CoachingTipType.CONFUSED, "title", "message", "binary tree"),
                null);
    }

    private static final class FakeRetryQueue implements CoachingHistoryRetryQueuePort {

        private final PendingCoachingHistoryRetry pending;
        private boolean acknowledged;
        private Instant rescheduledAt;

        private FakeRetryQueue(CoachingHistory history) {
            this.pending = new PendingCoachingHistoryRetry("entry", history, 0);
        }

        @Override
        public void enqueue(CoachingHistory history, Instant dueAt) {}

        @Override
        public List<PendingCoachingHistoryRetry> claimDue(int limit, Instant now, Duration lease) {
            return List.of(pending);
        }

        @Override
        public void acknowledge(PendingCoachingHistoryRetry pending) {
            acknowledged = true;
        }

        @Override
        public void reschedule(PendingCoachingHistoryRetry pending, Instant dueAt) {
            rescheduledAt = dueAt;
        }
    }
}
