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

    private final CoachingHistoryRetryQueuePort retryQueuePort;
    private final Clock clock;

    @Override
    public void store(CoachingHistory history) {
        try {
            retryQueuePort.enqueue(history, clock.instant());
        } catch (RuntimeException queueFailure) {
            log.warn(
                    "Coaching history durable queue is unavailable. sessionId={}, triggerId={}",
                    history.sessionId(),
                    history.triggerId(),
                    queueFailure);
            throw queueFailure;
        }
    }
}
