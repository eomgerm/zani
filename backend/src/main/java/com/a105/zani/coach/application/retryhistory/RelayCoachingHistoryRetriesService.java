package com.a105.zani.coach.application.retryhistory;

import java.time.Clock;
import java.time.Duration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.coach.application.port.CoachingHistoryRetryQueuePort;
import com.a105.zani.coach.application.storehistory.PersistCoachingHistoryUseCase;

@Slf4j
@Service
@RequiredArgsConstructor
class RelayCoachingHistoryRetriesService implements RelayCoachingHistoryRetriesUseCase {

    private static final int BATCH_SIZE = 20;
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(1);
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(5);

    private final CoachingHistoryRetryQueuePort retryQueuePort;
    private final PersistCoachingHistoryUseCase persistCoachingHistoryUseCase;
    private final Clock clock;

    @Override
    public int relay() {
        int processed = 0;
        for (PendingCoachingHistoryRetry pending : retryQueuePort.claimDue(BATCH_SIZE, clock.instant(), CLAIM_LEASE)) {
            try {
                persistCoachingHistoryUseCase.persist(pending.history());
                retryQueuePort.acknowledge(pending);
                processed++;
            } catch (RuntimeException persistenceFailure) {
                int nextAttempt = pending.attempt() + 1;
                retryQueuePort.reschedule(pending, clock.instant().plus(retryDelay(nextAttempt)));
                log.warn(
                        "Coaching history retry {} failed. sessionId={}, triggerId={}",
                        nextAttempt,
                        pending.history().sessionId(),
                        pending.history().triggerId(),
                        persistenceFailure);
            }
        }
        return processed;
    }

    private Duration retryDelay(int attempt) {
        long seconds = Math.min(1L << Math.min(attempt, 8), MAX_RETRY_DELAY.toSeconds());
        return Duration.ofSeconds(seconds);
    }
}
