package com.a105.zani.coach.application.port;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.a105.zani.coach.application.retryhistory.PendingCoachingHistoryRetry;
import com.a105.zani.coach.application.storehistory.CoachingHistory;

/** Durable retry boundary used when MySQL cannot store a completed coaching result immediately. */
public interface CoachingHistoryRetryQueuePort {

    void enqueue(CoachingHistory history, Instant dueAt);

    List<PendingCoachingHistoryRetry> claimDue(int limit, Instant now, Duration lease);

    void acknowledge(PendingCoachingHistoryRetry pending);

    void reschedule(PendingCoachingHistoryRetry pending, Instant dueAt);
}
