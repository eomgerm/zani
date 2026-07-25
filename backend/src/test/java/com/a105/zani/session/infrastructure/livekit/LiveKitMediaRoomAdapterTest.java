package com.a105.zani.session.infrastructure.livekit;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.a105.zani.session.application.port.MediaServerCredentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 세션이 소유한 room 이름 규칙·자격증명을 application port로 정확히 노출하는지 검증한다. */
class LiveKitMediaRoomAdapterTest {

    private static LiveKitMediaRoomAdapter adapterWith(
            String url, String apiKey, String apiSecret, String environment) {
        return new LiveKitMediaRoomAdapter(
                new LiveKitProperties(url, apiKey, apiSecret, environment, Duration.ofMinutes(10)));
    }

    @Test
    void room_이름은_환경과_세션_id로_구성된다() {
        LiveKitMediaRoomAdapter adapter = adapterWith("wss://livekit.example.com", "key", "secret", "dev");

        assertEquals("zani-dev-session-123", adapter.roomName(123L));
    }

    @Test
    void 자격증명을_그대로_노출한다() {
        LiveKitMediaRoomAdapter adapter = adapterWith("wss://livekit.example.com", "key", "secret", "local");

        MediaServerCredentials credentials = adapter.credentials();

        assertEquals("wss://livekit.example.com", credentials.serverUrl());
        assertEquals("key", credentials.apiKey());
        assertEquals("secret", credentials.apiSecret());
        assertTrue(credentials.isConfigured());
    }

    @Test
    void 자격증명이_비어_있으면_미설정으로_판정한다() {
        assertFalse(adapterWith("", "key", "secret", "local").credentials().isConfigured());
        assertFalse(adapterWith("wss://livekit.example.com", null, "secret", "local")
                .credentials()
                .isConfigured());
        assertFalse(adapterWith("wss://livekit.example.com", "key", "  ", "local")
                .credentials()
                .isConfigured());
    }
}
