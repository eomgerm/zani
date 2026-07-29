package com.a105.zani.common.infrastructure.gms;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GmsMockProfileGuardTest {

    private GmsProperties props(boolean mockEnabled) {
        return props(mockEnabled, "key");
    }

    private GmsProperties props(boolean mockEnabled, String apiKey) {
        return new GmsProperties(
                "https://gms.test",
                apiKey,
                mockEnabled,
                Duration.ofSeconds(10),
                Duration.ofSeconds(2),
                "whisper-1",
                Duration.ofSeconds(10),
                "ko");
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
    void toStringMasksApiKey() {
        String dumped = props(false, "super-secret-key").toString();
        assertFalse(dumped.contains("super-secret-key"), "설정 덤프에 API key 가 노출되면 안 된다: " + dumped);
        assertTrue(dumped.contains("****"));
        assertTrue(props(false, "").toString().contains("(unset)"));
        assertTrue(props(false, null).toString().contains("(unset)"));
    }

    @Test
    void allowsMissingApiKeyOutsideProd() {
        assertDoesNotThrow(() -> new GmsMockProfileGuard(environment("local"), props(false, "")));
        assertDoesNotThrow(() -> new GmsMockProfileGuard(environment("dev"), props(false, "")));
    }
}
