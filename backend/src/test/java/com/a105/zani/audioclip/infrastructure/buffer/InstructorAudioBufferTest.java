package com.a105.zani.audioclip.infrastructure.buffer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.a105.zani.audioclip.application.port.AudioClip;
import com.a105.zani.audioclip.domain.model.PcmAudioFormat;
import com.a105.zani.audioclip.infrastructure.encoding.PassThroughAudioEncoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstructorAudioBufferTest {

    private static final long SESSION_ID = 100L;
    private static final long OTHER_SESSION_ID = 200L;

    /** 전사 규격과 같은 16kHz. 이 형식이면 다운샘플이 항등이라 스냅샷 내용을 그대로 대조할 수 있다. */
    private static final PcmAudioFormat FORMAT = PcmAudioFormat.transcription();

    private static final int PER_SECOND = 32_000;
    private static final Duration WINDOW = Duration.ofSeconds(5);

    private InstructorAudioBuffer buffer() {
        // 이 클래스는 바이트 산술만 검증한다. 벽시계 패딩은 SilencePaddingTest 소관이라 시계를 멈춰 둔다.
        // 인코더는 항등이라 스냅샷 바이트를 넣은 값과 그대로 대조할 수 있다.
        return new InstructorAudioBuffer(
                FORMAT, WINDOW, 8, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), new PassThroughAudioEncoder());
    }

    /** value 로 채운 seconds 초 분량. 어느 구간이 남았는지 눈으로 확인하려고 쓴다. */
    private static byte[] seconds(double seconds, int value) {
        byte[] bytes = new byte[(int) (PER_SECOND * seconds)];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    /** 항등 인코더를 끼웠으므로 클립 바이트가 곧 떠낸 PCM 이다. */
    private static byte[] pcmOf(AudioClip clip) {
        return clip.audio();
    }

    private static AudioClip snapshot(InstructorAudioBuffer buffer, long sessionId) {
        return buffer.snapshot(sessionId, WINDOW).orElseThrow();
    }

    @Test
    void 아무것도_안_받았으면_비어_있다() {
        InstructorAudioBuffer buffer = buffer();

        assertEquals(0, buffer.availableMs(SESSION_ID));
        assertTrue(buffer.snapshot(SESSION_ID, WINDOW).isEmpty());
    }

    @Test
    void 받은_만큼의_길이를_보고한다() {
        InstructorAudioBuffer buffer = buffer();

        buffer.append(SESSION_ID, seconds(1, 1));
        assertEquals(1_000, buffer.availableMs(SESSION_ID));

        buffer.append(SESSION_ID, seconds(2.5, 2));
        assertEquals(3_500, buffer.availableMs(SESSION_ID));
    }

    @Test
    void 창을_넘으면_오래된_바이트부터_버린다() {
        InstructorAudioBuffer buffer = buffer();

        buffer.append(SESSION_ID, seconds(3, 1)); // 밀려날 부분
        buffer.append(SESSION_ID, seconds(5, 2)); // 남을 부분

        assertEquals(5_000, buffer.availableMs(SESSION_ID));
        assertArrayEquals(seconds(5, 2), pcmOf(snapshot(buffer, SESSION_ID)));
    }

    @Test
    void 한_조각이_창보다_커도_뒤에서_창_크기만_남긴다() {
        InstructorAudioBuffer buffer = buffer();
        byte[] huge = new byte[PER_SECOND * 10];
        for (int i = 0; i < huge.length; i += 1) {
            huge[i] = (byte) (i % 128);
        }

        buffer.append(SESSION_ID, huge);

        byte[] pcm = pcmOf(snapshot(buffer, SESSION_ID));
        assertEquals(PER_SECOND * 5, pcm.length);
        // 앞이 아니라 뒤(최근)가 남아야 한다.
        assertEquals((byte) ((huge.length - pcm.length) % 128), pcm[0]);
        assertEquals((byte) ((huge.length - 1) % 128), pcm[pcm.length - 1]);
    }

    @Test
    void 장시간_유입에도_메모리가_창_크기를_넘지_않는다() {
        InstructorAudioBuffer buffer = buffer();

        for (int i = 0; i < 200; i += 1) {
            buffer.append(SESSION_ID, seconds(1, i % 128));
            assertTrue(buffer.bufferedBytes(SESSION_ID) <= PER_SECOND * 5, "버퍼가 창 크기를 넘었다");
        }
        assertEquals(5_000, buffer.availableMs(SESSION_ID));
    }

    @Test
    void 요청한_창보다_길게_쌓였으면_요청한_만큼만_준다() {
        InstructorAudioBuffer buffer = buffer();
        buffer.append(SESSION_ID, seconds(5, 3));

        AudioClip clip = buffer.snapshot(SESSION_ID, Duration.ofSeconds(2)).orElseThrow();

        assertEquals(2_000, clip.actual().toMillis());
        assertEquals(PER_SECOND * 2, pcmOf(clip).length);
    }

    @Test
    void 요청한_창보다_짧게_쌓였으면_있는_만큼만_주고_actual로_알린다() {
        InstructorAudioBuffer buffer = buffer();
        buffer.append(SESSION_ID, seconds(2, 3));

        AudioClip clip = buffer.snapshot(SESSION_ID, Duration.ofMinutes(5)).orElseThrow();

        assertEquals(2_000, clip.actual().toMillis());
    }

    @Test
    void 세션끼리_섞이지_않는다() {
        InstructorAudioBuffer buffer = buffer();

        buffer.append(SESSION_ID, seconds(1, 1));
        buffer.append(OTHER_SESSION_ID, seconds(2, 9));

        assertEquals(1_000, buffer.availableMs(SESSION_ID));
        assertEquals(2_000, buffer.availableMs(OTHER_SESSION_ID));
        assertArrayEquals(seconds(2, 9), pcmOf(snapshot(buffer, OTHER_SESSION_ID)));
    }

    @Test
    void snapshot은_버퍼를_비우지_않는다() {
        InstructorAudioBuffer buffer = buffer();
        buffer.append(SESSION_ID, seconds(3, 7));

        AudioClip first = snapshot(buffer, SESSION_ID);
        AudioClip second = snapshot(buffer, SESSION_ID);

        assertArrayEquals(first.audio(), second.audio());
        assertEquals(3_000, buffer.availableMs(SESSION_ID));
    }

    @Test
    void release는_해당_세션_메모리를_반납한다() {
        InstructorAudioBuffer buffer = buffer();
        buffer.append(SESSION_ID, seconds(3, 1));
        buffer.append(OTHER_SESSION_ID, seconds(3, 2));

        buffer.release(SESSION_ID);

        assertEquals(0, buffer.availableMs(SESSION_ID));
        assertTrue(buffer.snapshot(SESSION_ID, WINDOW).isEmpty());
        assertEquals(3_000, buffer.availableMs(OTHER_SESSION_ID), "다른 세션은 그대로다");
    }

    @Test
    void 반납한_세션에_늦은_PCM이_도착해도_버퍼를_다시_만들지_않는다() {
        InstructorAudioBuffer buffer = buffer();
        buffer.append(SESSION_ID, seconds(1, 1));

        buffer.release(SESSION_ID);
        buffer.append(SESSION_ID, seconds(1, 2));

        assertEquals(0, buffer.availableMs(SESSION_ID));
        assertTrue(buffer.snapshot(SESSION_ID, WINDOW).isEmpty());
        assertEquals(0, buffer.activeSessions());
    }

    @Test
    void 빈_조각은_무시한다() {
        InstructorAudioBuffer buffer = buffer();

        buffer.append(SESSION_ID, new byte[0]);

        assertEquals(0, buffer.availableMs(SESSION_ID));
        assertTrue(buffer.snapshot(SESSION_ID, WINDOW).isEmpty());
    }

    @Test
    void 슬롯_상한을_넘는_세션은_버퍼_없이_진행한다() {
        // 상한이 없으면 세션당 수십 MB가 쌓여 컨테이너가 OOM으로 죽고 진행 중인 강의가 전부 끊긴다.
        InstructorAudioBuffer buffer = new InstructorAudioBuffer(
                FORMAT, WINDOW, 2, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), new PassThroughAudioEncoder());

        buffer.append(1L, seconds(1, 1));
        buffer.append(2L, seconds(1, 2));
        buffer.append(3L, seconds(1, 3));

        assertEquals(2, buffer.activeSessions());
        assertEquals(0, buffer.availableMs(3L), "상한 초과 세션은 코칭만 빠진다");
        assertEquals(1_000, buffer.availableMs(1L), "기존 세션은 영향받지 않는다");
    }

    @Test
    void 반납으로_빈_슬롯은_다음_세션이_쓴다() {
        InstructorAudioBuffer buffer = new InstructorAudioBuffer(
                FORMAT, WINDOW, 2, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), new PassThroughAudioEncoder());
        buffer.append(1L, seconds(1, 1));
        buffer.append(2L, seconds(1, 2));

        buffer.release(1L);
        buffer.append(3L, seconds(1, 3));

        assertEquals(1_000, buffer.availableMs(3L));
    }
}
