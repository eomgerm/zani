package com.a105.zani.audioclip.domain.model;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PcmAudioFormatTest {

    @Test
    void LiveKit_기본형식은_48kHz_모노_16비트다() {
        PcmAudioFormat format = PcmAudioFormat.liveKitDefault();

        assertEquals(48_000, format.sampleRate());
        assertEquals(1, format.channels());
        assertEquals(16, format.bitsPerSample());
        // 48000 * 1 * 2바이트 = 96,000 B/s
        assertEquals(96_000, format.bytesPerSecond());
    }

    @Test
    void 바이트_수를_재생시간으로_환산한다() {
        PcmAudioFormat format = PcmAudioFormat.liveKitDefault();

        assertEquals(0, format.durationMsOf(0));
        assertEquals(1_000, format.durationMsOf(96_000));
        assertEquals(300_000, format.durationMsOf(96_000 * 300));
        // 프레임 경계에 걸치지 않는 잔여 바이트는 내림한다.
        assertEquals(0, format.durationMsOf(95));
    }

    @Test
    void 재생시간을_바이트_수로_환산한다() {
        PcmAudioFormat format = PcmAudioFormat.liveKitDefault();

        assertEquals(96_000, format.bytesFor(Duration.ofSeconds(1)));
        assertEquals(96_000L * 300, format.bytesFor(Duration.ofMinutes(5)));
    }

    @Test
    void 프레임_크기는_채널과_비트depth의_곱이다() {
        assertEquals(2, PcmAudioFormat.liveKitDefault().frameBytes());
        assertEquals(4, new PcmAudioFormat(48_000, 2, 16).frameBytes());
    }

    @Test
    void 전사용_16kHz_모노는_초당_32000바이트다() {
        // Whisper 입력 규격. 48kHz 원본 대비 3분의 1로 줄어 메모리·전송량이 준다.
        PcmAudioFormat format = PcmAudioFormat.transcription();

        assertEquals(16_000, format.sampleRate());
        assertEquals(1, format.channels());
        assertEquals(32_000, format.bytesPerSecond());
    }

    @Test
    void 잘못된_형식은_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> new PcmAudioFormat(0, 1, 16));
        assertThrows(IllegalArgumentException.class, () -> new PcmAudioFormat(48_000, 0, 16));
        // s16le 만 다룬다. 다른 비트depth 는 링버퍼 산술이 어긋난다.
        assertThrows(IllegalArgumentException.class, () -> new PcmAudioFormat(48_000, 1, 24));
    }
}
