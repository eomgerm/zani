package com.a105.zani.coach.application.storehistory;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.coach.application.port.StoreCoachingHistoryPort;

@Service
@RequiredArgsConstructor
class PersistCoachingHistoryService implements PersistCoachingHistoryUseCase {

    private final StoreCoachingHistoryPort storeCoachingHistoryPort;

    @Override
    @Transactional
    public boolean persist(CoachingHistory history) {
        return storeCoachingHistoryPort.saveIfNew(history);
    }
}
