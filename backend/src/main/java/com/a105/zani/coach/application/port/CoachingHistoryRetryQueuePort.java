package com.a105.zani.coach.application.port;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.a105.zani.coach.application.retryhistory.PendingCoachingHistoryRetry;
import com.a105.zani.coach.application.storehistory.CoachingHistory;

/** Durable handoff boundary between the real-time coaching path and eventual MySQL persistence. */
public interface CoachingHistoryRetryQueuePort {

    void enqueue(CoachingHistory history, Instant dueAt);

    List<PendingCoachingHistoryRetry> claimDue(int limit, Instant now, Duration lease);

    void acknowledge(PendingCoachingHistoryRetry pending);

    void reschedule(PendingCoachingHistoryRetry pending, Instant dueAt);
}
