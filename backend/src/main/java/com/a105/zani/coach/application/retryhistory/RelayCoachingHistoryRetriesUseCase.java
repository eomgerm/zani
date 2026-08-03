package com.a105.zani.coach.application.retryhistory;

/** Retries coaching-history writes that could not reach MySQL on the live path. */
public interface RelayCoachingHistoryRetriesUseCase {

    int relay();
}
