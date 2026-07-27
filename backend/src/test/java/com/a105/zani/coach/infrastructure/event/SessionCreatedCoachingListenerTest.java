package com.a105.zani.coach.infrastructure.event;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.a105.zani.coach.application.checkavailability.CheckCoachingAvailabilityUseCase;
import com.a105.zani.session.application.create.SessionCreatedEvent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionCreatedCoachingListenerTest {

    private static final long SESSION_ID = 7L;

    @Test
    void delegatesSessionIdToUseCase() {
        AtomicLong captured = new AtomicLong(-1);
        CheckCoachingAvailabilityUseCase useCase = command -> {
            captured.set(command.sessionId());
            return true;
        };
        SessionCreatedCoachingListener listener = new SessionCreatedCoachingListener(useCase);

        listener.onSessionCreated(new SessionCreatedEvent(SESSION_ID, 100L));

        assertEquals(SESSION_ID, captured.get());
    }

    @Test
    void swallowsUseCaseException() {
        CheckCoachingAvailabilityUseCase failing = command -> {
            throw new RuntimeException("boom");
        };
        SessionCreatedCoachingListener listener = new SessionCreatedCoachingListener(failing);

        assertDoesNotThrow(() -> listener.onSessionCreated(new SessionCreatedEvent(SESSION_ID, 100L)));
    }
}
