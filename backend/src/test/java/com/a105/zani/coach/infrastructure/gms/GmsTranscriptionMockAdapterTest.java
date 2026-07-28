package com.a105.zani.coach.infrastructure.gms;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.a105.zani.coach.application.port.AudioClip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GmsTranscriptionMockAdapterTest {

    private static final Instant TO = Instant.parse("2026-07-28T02:00:00Z");

    @Test
    void returnsFixedTranscriptWithoutRealCall() {
        AudioClip clip = new AudioClip(
                "mp3".getBytes(StandardCharsets.UTF_8), TO.minusSeconds(120), TO, Duration.ofSeconds(120));

        var result = new GmsTranscriptionMockAdapter().transcribe(clip).orElseThrow();

        assertEquals(GmsTranscriptionMockAdapter.MOCK_TRANSCRIPT, result.text());
    }

    @Test
    void returnsEmptyForEmptyAudio() {
        AudioClip empty = new AudioClip(new byte[0], TO, TO, Duration.ZERO);

        assertTrue(new GmsTranscriptionMockAdapter().transcribe(empty).isEmpty());
    }
}
