package com.a105.zani.coach.application.port;

import com.a105.zani.coach.application.storehistory.CoachingHistory;

/** Persistence boundary for an anonymous coaching-history row. */
public interface StoreCoachingHistoryPort {

    boolean saveIfNew(CoachingHistory history);
}
