package com.a105.zani.coach.application.storehistory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.a105.zani.attention.application.port.CoachingTip;
import com.a105.zani.attention.application.port.CoachingTipType;
import com.a105.zani.coach.application.port.CoachingHistoryRetryQueuePort;
import com.a105.zani.coach.application.retryhistory.PendingCoachingHistoryRetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoreCoachingHistoryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-31T01:00:00Z");

    @Test
    void handsOffTheCompleteSessionOwnedResultForAsynchronousPersistence() {
        CoachingHistory history = history();
        CapturingRetryQueue retries = new CapturingRetryQueue();
        StoreCoachingHistoryService service =
                new StoreCoachingHistoryService(retries, Clock.fixed(NOW, ZoneOffset.UTC));

        service.store(history);

        assertThat(retries.histories).containsExactly(history);
        assertThat(retries.dueTimes).containsExactly(NOW);
    }

    @Test
    void propagatesAQueueFailureSoTheCallerCanReportThatTheHistoryWasNotHandedOff() {
        CoachingHistory history = history();
        CoachingHistoryRetryQueuePort unavailableQueue = new CapturingRetryQueue() {
            @Override
            public void enqueue(CoachingHistory ignored, Instant dueAt) {
                throw new IllegalStateException("redis down");
            }
        };
        StoreCoachingHistoryService service =
                new StoreCoachingHistoryService(unavailableQueue, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.store(history))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis down");
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

    private static class CapturingRetryQueue implements CoachingHistoryRetryQueuePort {

        private final List<CoachingHistory> histories = new ArrayList<>();
        private final List<Instant> dueTimes = new ArrayList<>();

        @Override
        public void enqueue(CoachingHistory history, Instant dueAt) {
            histories.add(history);
            dueTimes.add(dueAt);
        }

        @Override
        public List<PendingCoachingHistoryRetry> claimDue(int limit, Instant now, Duration lease) {
            return List.of();
        }

        @Override
        public void acknowledge(PendingCoachingHistoryRetry pending) {}

        @Override
        public void reschedule(PendingCoachingHistoryRetry pending, Instant dueAt) {}
    }
}
