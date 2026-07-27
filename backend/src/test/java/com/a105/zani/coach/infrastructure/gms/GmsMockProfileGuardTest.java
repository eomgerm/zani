package com.a105.zani.coach.infrastructure.gms;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GmsMockProfileGuardTest {

    private GmsProperties props(boolean mockEnabled) {
        return new GmsProperties(
                "https://gms.test",
                "key",
                "whisper-1",
                "gpt-4.1-nano",
                mockEnabled,
                Duration.ofSeconds(3),
                Duration.ofSeconds(2));
    }

    private StandardEnvironment environment(String... activeProfiles) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles(activeProfiles);
        return environment;
    }

    @Test
    void failsFastWhenMockEnabledInProd() {
        assertThrows(IllegalStateException.class, () -> new GmsMockProfileGuard(environment("prod"), props(true)));
    }

    @Test
    void allowsMockDisabledInProd() {
        assertDoesNotThrow(() -> new GmsMockProfileGuard(environment("prod"), props(false)));
    }

    @Test
    void allowsMockEnabledInLocal() {
        assertDoesNotThrow(() -> new GmsMockProfileGuard(environment("local"), props(true)));
    }
}
