package com.a105.zani.audioclip.infrastructure.buffer;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.a105.zani.audioclip.application.port.AudioClip;
import com.a105.zani.audioclip.domain.model.PcmAudioFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 벽시계 정렬 검증.
 *
 * <p>Egress 는 마이크가 음소거되면 프레임을 보내지 않는다. 바이트만 세면 "최근 300초"가 실제로는 훨씬 과거부터 시작하는데, 에러 없이 조용히 틀리기 때문에 짧은 수동 테스트로는 드러나지 않는다.
 * 그래서 공백을 무음으로 메워 바이트 수와 벽시계를 일치시킨다.
 */
class InstructorAudioBufferSilencePaddingTest {

    private static final long SESSION_ID = 100L;

    /** 전사 규격과 같은 16kHz(모노·16bit). 이 형식이면 다운샘플이 항등이라 내용을 그대로 대조할 수 있다. */
    private static final PcmAudioFormat FORMAT = PcmAudioFormat.transcription();

    private static final int PER_SECOND = 32_000;
    private static final Duration WINDOW = Duration.ofSeconds(10);

    private final MutableClock clock = new MutableClock(0);
    private final InstructorAudioBuffer buffer = new InstructorAudioBuffer(FORMAT, WINDOW, 8, clock);

    /** seconds 초 분량의 발화(0이 아닌 값으로 채워 무음과 구분한다). */
    private static byte[] speech(double seconds) {
        byte[] bytes = new byte[(int) (PER_SECOND * seconds)];
        java.util.Arrays.fill(bytes, (byte) 7);
        return bytes;
    }

    /** WAV 헤더를 뗀 실제 오디오 바이트. */
    private static byte[] pcmOf(long sessionId, InstructorAudioBuffer buffer) {
        AudioClip clip = buffer.snapshot(sessionId, WINDOW).orElseThrow();
        return java.util.Arrays.copyOfRange(
                clip.wav(), com.a105.zani.audioclip.domain.model.WavEncoder.HEADER_BYTES, clip.wav().length);
    }

    private static int silenceCount(byte[] pcm) {
        int count = 0;
        for (byte b : pcm) {
            if (b == 0) {
                count += 1;
            }
        }
        return count;
    }

    @Test
    void 음소거_구간이_무음으로_메워져_바이트와_벽시계가_일치한다() {
        // 0~2초 발화
        buffer.append(SESSION_ID, speech(2));
        clock.advance(2_000);

        // 2~5초 음소거: 프레임이 오지 않는다. 틱만 돈다.
        clock.advance(3_000);
        buffer.padSilence();

        // 5초 시점이므로 5초(500바이트)를 갖고 있어야 한다.
        assertEquals(5_000, buffer.availableMs(SESSION_ID));
        assertEquals(PER_SECOND * 3, silenceCount(pcmOf(SESSION_ID, buffer)));
    }

    @Test
    void 음소거_해제_후_발화가_공백_뒤에_이어진다() {
        buffer.append(SESSION_ID, speech(1)); // 0~1초
        clock.advance(1_000);

        clock.advance(2_000); // 1~3초 음소거
        buffer.padSilence();

        buffer.append(SESSION_ID, speech(1)); // 3~4초
        clock.advance(1_000);

        byte[] pcm = pcmOf(SESSION_ID, buffer);
        assertEquals(PER_SECOND * 4, pcm.length);
        assertEquals(7, pcm[0], "첫 발화");
        assertEquals(0, pcm[(int) (PER_SECOND * 1.5)], "음소거 구간");
        assertEquals(7, pcm[(int) (PER_SECOND * 3.5)], "재개 후 발화");
    }

    @Test
    void 창보다_긴_음소거는_버퍼를_무음으로_채우되_창을_넘지_않는다() {
        buffer.append(SESSION_ID, speech(2));
        clock.advance(2_000);

        // 창(10초)의 10배를 음소거로 흘려보낸다.
        clock.advance(100_000);
        buffer.padSilence();

        assertEquals(10_000, buffer.availableMs(SESSION_ID));
        assertTrue(buffer.bufferedBytes(SESSION_ID) <= PER_SECOND * 10, "창 크기를 넘지 않는다");
        byte[] pcm = pcmOf(SESSION_ID, buffer);
        assertEquals(pcm.length, silenceCount(pcm), "오래된 발화는 전부 밀려났다");
    }

    @Test
    void 정상_수신_중에는_지터를_무음으로_메우지_않는다() {
        // 100ms 틱보다 촘촘히 오가는 정상 스트림. 허용 오차 안의 어긋남은 무음을 넣지 않는다.
        for (int i = 0; i < 20; i += 1) {
            buffer.append(SESSION_ID, speech(0.1)); // 0.1초분
            clock.advance(100);
            buffer.padSilence();
        }

        byte[] pcm = pcmOf(SESSION_ID, buffer);
        assertEquals(0, silenceCount(pcm), "발화 사이에 무음이 끼면 전사 품질이 떨어진다");
    }

    @Test
    void capture는_구간의_벽시계_시각을_함께_준다() {
        clock.setEpochMilli(1_000_000);
        buffer.append(SESSION_ID, speech(3)); // 3초
        clock.advance(3_000);

        AudioClip clip = buffer.snapshot(SESSION_ID, WINDOW).orElseThrow();

        assertEquals(1_000_000, clip.fromEpochMs());
        assertEquals(1_003_000, clip.toEpochMs());
        assertEquals(3_000, clip.actual().toMillis());
    }

    @Test
    void 첫_프레임이_오기_전에는_패딩하지_않는다() {
        clock.advance(60_000);

        buffer.padSilence();

        // 스트림이 시작되지도 않은 세션에 무음을 쌓지 않는다.
        assertEquals(0, buffer.availableMs(SESSION_ID));
    }

    @Test
    void 반납한_세션은_패딩_대상에서_빠진다() {
        buffer.append(SESSION_ID, speech(1));
        clock.advance(1_000);
        buffer.release(SESSION_ID);

        clock.advance(5_000);
        buffer.padSilence();

        assertEquals(0, buffer.availableMs(SESSION_ID));
    }

    /** 테스트가 시간을 직접 움직이는 시계. */
    private static final class MutableClock extends java.time.Clock {
        private java.time.Instant instant;

        private MutableClock(long epochMilli) {
            this.instant = java.time.Instant.ofEpochMilli(epochMilli);
        }

        private void advance(long millis) {
            instant = instant.plusMillis(millis);
        }

        private void setEpochMilli(long epochMilli) {
            instant = java.time.Instant.ofEpochMilli(epochMilli);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return instant;
        }
    }
}
