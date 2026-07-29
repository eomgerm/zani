package com.a105.zani.audioclip.infrastructure.config;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 설정 검증.
 *
 * <p>여기서 막지 못한 잘못된 값은 기동을 통과하고 버퍼도 정상으로 보이다가 첫 코칭 트리거에서야 터진다. 그 시점의 예외는 {@code BusinessException} 이 아니라 500 으로 새어 나가므로,
 * 잘못된 조합은 기동 시점에 실패시킨다.
 */
class AudioClipPropertiesTest {

    private static AudioClipProperties withSampleRate(Integer sampleRate) {
        return new AudioClipProperties(null, null, sampleRate, null, null);
    }

    @Test
    @DisplayName("16kHz 의 배수가 아닌 샘플레이트는 기동 시점에 거부한다")
    void rejectsSampleRateThatIsNotAMultipleOfTranscriptionRate() {
        // 다운샘플이 정수배 데시메이션이라 44.1kHz 계열은 다룰 수 없다.
        assertThrows(IllegalArgumentException.class, () -> withSampleRate(44_100));
        assertThrows(IllegalArgumentException.class, () -> withSampleRate(22_050));
    }

    @Test
    @DisplayName("16kHz 의 배수는 통과한다")
    void acceptsMultiplesOfTranscriptionRate() {
        assertEquals(48_000, withSampleRate(48_000).sampleRate());
        assertEquals(32_000, withSampleRate(32_000).sampleRate());
        assertEquals(16_000, withSampleRate(16_000).sampleRate());
    }

    @Test
    @DisplayName("비거나 0 이하면 기본값 48kHz 로 채운다")
    void fallsBackToDefaultSampleRate() {
        assertEquals(48_000, withSampleRate(null).sampleRate());
        assertEquals(48_000, withSampleRate(0).sampleRate());
        assertEquals(48_000, withSampleRate(-1).sampleRate());
    }

    @Test
    @DisplayName("나머지 값도 비어 있으면 기본값으로 채운다")
    void fillsRemainingDefaults() {
        AudioClipProperties properties = new AudioClipProperties(null, null, null, null, null);

        assertEquals(Duration.ofMinutes(5), properties.window());
        assertEquals(Duration.ofMinutes(1), properties.minTranscribable());
        assertEquals(8, properties.maxSessions());
        assertEquals("ws://127.0.0.1:18080/internal/audio/{sessionId}", properties.streamUrlTemplate());
    }
}
