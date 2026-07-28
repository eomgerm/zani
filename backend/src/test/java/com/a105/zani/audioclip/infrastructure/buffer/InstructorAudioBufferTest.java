package com.a105.zani.audioclip.infrastructure.buffer;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.a105.zani.audioclip.application.port.CapturedAudio;
import com.a105.zani.audioclip.domain.model.PcmAudioFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstructorAudioBufferTest {

    private static final long SESSION_ID = 100L;
    private static final long OTHER_SESSION_ID = 200L;

    /** 테스트를 읽기 쉽게 1초 = 100바이트인 작은 형식을 쓴다(50Hz·모노·16bit). */
    private static final PcmAudioFormat TINY = new PcmAudioFormat(50, 1, 16);

    private static final Duration WINDOW = Duration.ofSeconds(5);

    private InstructorAudioBuffer buffer() {
        // 이 클래스는 바이트 산술만 검증한다. 벽시계 패딩은 SilencePaddingTest 소관이라 시계를 멈춰 둔다.
        return new InstructorAudioBuffer(
                TINY, WINDOW, java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC));
    }

    /** 값이 value 로 채워진 length 바이트. 어느 구간이 남았는지 눈으로 확인하려고 쓴다. */
    private static byte[] chunk(int length, int value) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    @Test
    void 아무것도_안_받았으면_비어_있다() {
        InstructorAudioBuffer buffer = buffer();

        assertEquals(0, buffer.availableMs(SESSION_ID));
        assertTrue(buffer.capture(SESSION_ID).isEmpty());
    }

    @Test
    void 받은_만큼의_길이를_보고한다() {
        InstructorAudioBuffer buffer = buffer();

        buffer.append(SESSION_ID, chunk(100, 1)); // 1초
        assertEquals(1_000, buffer.availableMs(SESSION_ID));

        buffer.append(SESSION_ID, chunk(250, 2)); // 2.5초 추가
        assertEquals(3_500, buffer.availableMs(SESSION_ID));
    }

    @Test
    void 창을_넘으면_오래된_바이트부터_버린다() {
        InstructorAudioBuffer buffer = buffer();

        buffer.append(SESSION_ID, chunk(300, 1)); // 3초 (버려질 부분)
        buffer.append(SESSION_ID, chunk(500, 2)); // 5초 (남을 부분)

        // 창이 5초라 마지막 500바이트만 남는다.
        assertEquals(5_000, buffer.availableMs(SESSION_ID));
        CapturedAudio captured = buffer.capture(SESSION_ID).orElseThrow();
        assertEquals(500, captured.pcm().length);
        assertArrayEquals(chunk(500, 2), captured.pcm());
    }

    @Test
    void 한_조각이_창보다_커도_뒤에서_창_크기만_남긴다() {
        InstructorAudioBuffer buffer = buffer();
        byte[] huge = new byte[1_000];
        for (int i = 0; i < huge.length; i += 1) {
            huge[i] = (byte) (i % 128);
        }

        buffer.append(SESSION_ID, huge);

        CapturedAudio captured = buffer.capture(SESSION_ID).orElseThrow();
        assertEquals(500, captured.pcm().length);
        // 앞이 아니라 뒤(최근)가 남아야 한다.
        assertEquals((byte) (500 % 128), captured.pcm()[0]);
        assertEquals((byte) (999 % 128), captured.pcm()[499]);
    }

    @Test
    void 장시간_유입에도_메모리가_창_크기를_넘지_않는다() {
        InstructorAudioBuffer buffer = buffer();

        // 창(5초=500B)의 200배를 흘려보낸다.
        for (int i = 0; i < 1_000; i += 1) {
            buffer.append(SESSION_ID, chunk(100, i % 128));
            assertTrue(buffer.bufferedBytes(SESSION_ID) <= 500, "버퍼가 창 크기를 넘었다");
        }
        assertEquals(5_000, buffer.availableMs(SESSION_ID));
    }

    @Test
    void 세션끼리_섞이지_않는다() {
        InstructorAudioBuffer buffer = buffer();

        buffer.append(SESSION_ID, chunk(100, 1));
        buffer.append(OTHER_SESSION_ID, chunk(200, 9));

        assertEquals(1_000, buffer.availableMs(SESSION_ID));
        assertEquals(2_000, buffer.availableMs(OTHER_SESSION_ID));
        assertArrayEquals(
                chunk(200, 9), buffer.capture(OTHER_SESSION_ID).orElseThrow().pcm());
    }

    @Test
    void capture는_버퍼를_비우지_않는다() {
        InstructorAudioBuffer buffer = buffer();
        buffer.append(SESSION_ID, chunk(300, 7));

        CapturedAudio first = buffer.capture(SESSION_ID).orElseThrow();
        CapturedAudio second = buffer.capture(SESSION_ID).orElseThrow();

        assertArrayEquals(first.pcm(), second.pcm());
        assertEquals(3_000, buffer.availableMs(SESSION_ID));
    }

    @Test
    void capture는_형식과_길이를_함께_준다() {
        InstructorAudioBuffer buffer = buffer();
        buffer.append(SESSION_ID, chunk(250, 3));

        CapturedAudio captured = buffer.capture(SESSION_ID).orElseThrow();

        assertEquals(TINY, captured.format());
        assertEquals(2_500, captured.durationMs());
    }

    @Test
    void release는_해당_세션_메모리를_반납한다() {
        InstructorAudioBuffer buffer = buffer();
        buffer.append(SESSION_ID, chunk(300, 1));
        buffer.append(OTHER_SESSION_ID, chunk(300, 2));

        buffer.release(SESSION_ID);

        assertEquals(0, buffer.availableMs(SESSION_ID));
        assertTrue(buffer.capture(SESSION_ID).isEmpty());
        // 다른 세션은 그대로다.
        assertEquals(3_000, buffer.availableMs(OTHER_SESSION_ID));
    }

    @Test
    void 빈_조각은_무시한다() {
        InstructorAudioBuffer buffer = buffer();

        buffer.append(SESSION_ID, new byte[0]);

        assertEquals(0, buffer.availableMs(SESSION_ID));
        assertFalse(buffer.capture(SESSION_ID).isPresent());
    }

    @Test
    void 프레임_경계에_안_맞는_잔여_바이트는_길이_계산에서_내림한다() {
        InstructorAudioBuffer buffer = buffer();

        buffer.append(SESSION_ID, chunk(101, 1)); // 100바이트=1초 + 1바이트

        assertEquals(1_000, buffer.availableMs(SESSION_ID));
    }
}
