package com.a105.zani.coach.application.storehistory;

/**
 * Hands a completed coaching result to durable background persistence.
 *
 * <p>The real-time polling path calls this port directly, so implementations must not perform MySQL I/O on the
 * caller thread.
 */
public interface StoreCoachingHistoryUseCase {

    void store(CoachingHistory history);
}
