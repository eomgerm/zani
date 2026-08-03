package com.a105.zani.coach.application.storehistory;

/** Transactional database write used by both the live path and the retry relay. */
public interface PersistCoachingHistoryUseCase {

    boolean persist(CoachingHistory history);
}
