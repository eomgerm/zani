package com.a105.zani.session.infrastructure.livekit;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.a105.zani.session.application.port.IssuedMediaToken;
import com.a105.zani.session.application.port.MediaTokenRequest;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 어댑터는 오프라인에서 JWT를 서명한다(LiveKit 서버 연결 불필요). */
class LiveKitTokenAdapterTest {

    private final LiveKitProperties properties = new LiveKitProperties(
            "wss://livekit.example.com",
            "devkey",
            "dev-secret-must-be-long-enough-for-hmac-signing",
            "test",
            Duration.ofMinutes(10));

    private final LiveKitTokenAdapter adapter = new LiveKitTokenAdapter(properties);

    @Test
    void roomName을_환경과_sessionId로_구성하고_JWT를_발급한다() {
        IssuedMediaToken issued =
                adapter.issue(new MediaTokenRequest("p-456", "홍길동", SessionParticipantRole.STUDENT, 123L));

        assertEquals("wss://livekit.example.com", issued.liveKitUrl());
        assertEquals("zani-test-session-123", issued.roomName());
        assertNotNull(issued.expiresAt());
        assertNotNull(issued.accessToken());
        assertFalse(issued.accessToken().isBlank());
        // JWT는 header.payload.signature 3부분이다.
        assertEquals(3, issued.accessToken().split("\\.").length);
    }

    @Test
    void 자격증명이_비어있으면_발급을_거부한다() {
        LiveKitTokenAdapter unconfigured =
                new LiveKitTokenAdapter(new LiveKitProperties("wss://x", "", "", "test", Duration.ofMinutes(10)));

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> unconfigured.issue(new MediaTokenRequest("p-1", "n", SessionParticipantRole.STUDENT, 1L)));
    }
}
