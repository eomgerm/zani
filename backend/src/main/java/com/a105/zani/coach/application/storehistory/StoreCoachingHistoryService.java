package com.a105.zani.coach.application.storehistory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.coach.application.port.StoreCoachingHistoryPort;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoreCoachingHistoryService implements StoreCoachingHistoryUseCase {

    private final StoreCoachingHistoryPort storeCoachingHistoryPort;

    @Override
    @Transactional
    public void store(CoachingHistory history) {
        if (!storeCoachingHistoryPort.saveIfNew(history)) {
            log.debug("Coaching history already exists for trigger {}", history.triggerId());
        }
    }
}
