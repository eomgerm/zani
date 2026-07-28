package com.a105.zani.coach.infrastructure.gms;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GmsMockProfileGuardTest {

    private GmsProperties props(boolean mockEnabled) {
        return props(mockEnabled, "key");
    }

    private GmsProperties props(boolean mockEnabled, String apiKey) {
        return new GmsProperties(
                "https://gms.test", apiKey, mockEnabled, Duration.ofSeconds(10), Duration.ofSeconds(2));
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

    @Test
    void failsFastWhenApiKeyIsMissingInProd() {
        assertThrows(IllegalStateException.class, () -> new GmsMockProfileGuard(environment("prod"), props(false, "")));
        assertThrows(
                IllegalStateException.class, () -> new GmsMockProfileGuard(environment("prod"), props(false, "   ")));
        assertThrows(
                IllegalStateException.class, () -> new GmsMockProfileGuard(environment("prod"), props(false, null)));
    }

    @Test
    void allowsMissingApiKeyOutsideProd() {
        assertDoesNotThrow(() -> new GmsMockProfileGuard(environment("local"), props(false, "")));
        assertDoesNotThrow(() -> new GmsMockProfileGuard(environment("dev"), props(false, "")));
    }
}
