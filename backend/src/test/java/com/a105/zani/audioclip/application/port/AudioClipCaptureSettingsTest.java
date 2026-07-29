package com.a105.zani.audioclip.application.port;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AudioClipCaptureSettingsTest {

    @Test
    @DisplayName("정상 조합은 그대로 담는다")
    void keepsValidValues() {
        AudioClipCaptureSettings settings = new AudioClipCaptureSettings(Duration.ofMinutes(5), Duration.ofMinutes(1));

        assertEquals(Duration.ofMinutes(5), settings.window());
        assertEquals(Duration.ofMinutes(1), settings.minTranscribable());
    }

    @Test
    @DisplayName("하한이 창보다 길면 거부한다 — 전사가 영구히 일어나지 않는 조합")
    void rejectsMinimumLongerThanWindow() {
        // 버퍼가 가득 차도 조건을 만족할 수 없다. 기동 시 터뜨려야 운영 중 조용한 무동작을 피한다.
        assertThrows(
                IllegalArgumentException.class,
                () -> new AudioClipCaptureSettings(Duration.ofMinutes(1), Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("창이 0이거나 음수면 거부한다")
    void rejectsNonPositiveWindow() {
        assertThrows(IllegalArgumentException.class, () -> new AudioClipCaptureSettings(Duration.ZERO, Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AudioClipCaptureSettings(Duration.ofMinutes(-1), Duration.ZERO));
    }

    @Test
    @DisplayName("null 은 거부한다")
    void rejectsNulls() {
        assertThrows(IllegalArgumentException.class, () -> new AudioClipCaptureSettings(null, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new AudioClipCaptureSettings(Duration.ofMinutes(5), null));
    }

    @Test
    @DisplayName("하한 0은 허용한다 — 확보량 검사를 끄는 설정")
    void allowsZeroMinimum() {
        assertEquals(
                Duration.ZERO, new AudioClipCaptureSettings(Duration.ofMinutes(5), Duration.ZERO).minTranscribable());
    }
}
