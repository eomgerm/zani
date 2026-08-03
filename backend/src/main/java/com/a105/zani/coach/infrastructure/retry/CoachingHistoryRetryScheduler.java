package com.a105.zani.coach.infrastructure.retry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.a105.zani.coach.application.retryhistory.RelayCoachingHistoryRetriesUseCase;

@Slf4j
@Component
@RequiredArgsConstructor
public class CoachingHistoryRetryScheduler {

    private final RelayCoachingHistoryRetriesUseCase relayUseCase;

    @Scheduled(fixedDelayString = "${coach.history-retry-delay}")
    public void relay() {
        try {
            relayUseCase.relay();
        } catch (RuntimeException exception) {
            log.warn("Coaching history retry pass failed: {}", exception.getMessage());
        }
    }
}
