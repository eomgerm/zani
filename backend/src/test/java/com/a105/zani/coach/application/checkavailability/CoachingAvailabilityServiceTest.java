package com.a105.zani.coach.application.checkavailability;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.a105.zani.coach.application.port.CoachingAvailabilityPort;
import com.a105.zani.coach.application.port.GmsHealthPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoachingAvailabilityServiceTest {

    private static final long SESSION_ID = 42L;

    @Test
    void storesTrueWhenWhisperAvailable() {
        FakeAvailabilityPort store = new FakeAvailabilityPort();
        CoachingAvailabilityService service = new CoachingAvailabilityService(() -> true, store);

        boolean result = service.check(new CheckCoachingAvailabilityCommand(SESSION_ID));

        assertTrue(result);
        assertEquals(Boolean.TRUE, store.find(SESSION_ID).orElse(null));
    }

    @Test
    void storesFalseWhenWhisperUnavailable() {
        FakeAvailabilityPort store = new FakeAvailabilityPort();
        CoachingAvailabilityService service = new CoachingAvailabilityService(() -> false, store);

        boolean result = service.check(new CheckCoachingAvailabilityCommand(SESSION_ID));

        assertFalse(result);
        assertEquals(Boolean.FALSE, store.find(SESSION_ID).orElse(null));
    }

    @Test
    void swallowsHealthErrorAndStoresFalse() {
        FakeAvailabilityPort store = new FakeAvailabilityPort();
        GmsHealthPort failing = () -> {
            throw new RuntimeException("gms down");
        };
        CoachingAvailabilityService service = new CoachingAvailabilityService(failing, store);

        boolean result = service.check(new CheckCoachingAvailabilityCommand(SESSION_ID));

        assertFalse(result);
        assertEquals(Boolean.FALSE, store.find(SESSION_ID).orElse(null));
    }

    private static final class FakeAvailabilityPort implements CoachingAvailabilityPort {
        private Boolean stored;

        @Override
        public void store(long sessionId, boolean available) {
            this.stored = available;
        }

        @Override
        public Optional<Boolean> find(long sessionId) {
            return Optional.ofNullable(stored);
        }
    }
}
