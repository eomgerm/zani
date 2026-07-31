package com.a105.zani.coach.application.storehistory;

import java.time.Clock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.coach.application.port.CoachingHistoryRetryQueuePort;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoreCoachingHistoryService implements StoreCoachingHistoryUseCase {

    private final PersistCoachingHistoryUseCase persistCoachingHistoryUseCase;
    private final CoachingHistoryRetryQueuePort retryQueuePort;
    private final Clock clock;

    @Override
    public void store(CoachingHistory history) {
        boolean retryQueued = enqueueForRecovery(history);
        try {
            if (!persistCoachingHistoryUseCase.persist(history)) {
                log.debug(
                        "Coaching history already exists for session {} trigger {}",
                        history.sessionId(),
                        history.triggerId());
            }
        } catch (RuntimeException persistenceFailure) {
            if (!retryQueued) {
                throw persistenceFailure;
            }
            log.warn(
                    "Coaching history will be retried. sessionId={}, triggerId={}",
                    history.sessionId(),
                    history.triggerId(),
                    persistenceFailure);
        }
    }

    private boolean enqueueForRecovery(CoachingHistory history) {
        try {
            retryQueuePort.enqueue(history, clock.instant());
            return true;
        } catch (RuntimeException queueFailure) {
            log.warn(
                    "Coaching history retry queue is unavailable. sessionId={}, triggerId={}",
                    history.sessionId(),
                    history.triggerId(),
                    queueFailure);
            return false;
        }
    }
}
