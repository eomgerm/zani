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

class StoreCoachingHistoryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-31T01:00:00Z");

    @Test
    void queuesTheCompleteSessionOwnedResultWhenMysqlIsUnavailable() {
        CoachingHistory history = history();
        CapturingRetryQueue retries = new CapturingRetryQueue();
        StoreCoachingHistoryService service = new StoreCoachingHistoryService(
                ignored -> {
                    throw new IllegalStateException("mysql down");
                },
                retries,
                Clock.fixed(NOW, ZoneOffset.UTC));

        service.store(history);

        assertThat(retries.histories).containsExactly(history);
        assertThat(retries.dueTimes).containsExactly(NOW);
    }

    @Test
    void writesAheadToTheRetryQueueEvenWhenMysqlIsAvailable() {
        CoachingHistory history = history();
        CapturingRetryQueue retries = new CapturingRetryQueue();
        StoreCoachingHistoryService service =
                new StoreCoachingHistoryService(ignored -> true, retries, Clock.fixed(NOW, ZoneOffset.UTC));

        service.store(history);

        assertThat(retries.histories).containsExactly(history);
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

    private static final class CapturingRetryQueue implements CoachingHistoryRetryQueuePort {

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
