package com.a105.zani.coach.application.retryhistory;

import com.a105.zani.coach.application.storehistory.CoachingHistory;

/** One leased retry entry. queueEntry is an opaque token owned by the queue adapter. */
public record PendingCoachingHistoryRetry(String queueEntry, CoachingHistory history, int attempt) {

    public PendingCoachingHistoryRetry {
        if (queueEntry == null || queueEntry.isBlank() || history == null || attempt < 0) {
            throw new IllegalArgumentException("retry entry, history, and non-negative attempt are required");
        }
    }
}
