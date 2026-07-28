package com.a105.zani.audioclip.application.captureclip;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.a105.zani.audioclip.application.exception.AudioClipTranscriptionFailedException;
import com.a105.zani.audioclip.application.port.AudioClip;
import com.a105.zani.audioclip.application.port.AudioTranscriptionPort;
import com.a105.zani.audioclip.application.port.InstructorAudioBufferPort;
import com.a105.zani.audioclip.infrastructure.config.AudioClipProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureAudioClipServiceTest {

    private static final long SESSION_ID = 100L;
    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final Duration MIN_TRANSCRIBABLE = Duration.ofMinutes(1);

    /** 버퍼(인코더)가 정한 형식. 서비스는 이 값을 그대로 전사 포트에 넘겨야 한다. */
    private static final String CLIP_CONTENT_TYPE = "audio/mpeg";

    private final FakeBuffer buffer = new FakeBuffer();
    private final FakeTranscriptionPort transcription = new FakeTranscriptionPort();

    private CaptureAudioClipService service;

    @BeforeEach
    void setUp() {
        service = new CaptureAudioClipService(
                buffer, transcription, new AudioClipProperties(WINDOW, MIN_TRANSCRIBABLE, null, null, null));
    }

    /** durationMs 만큼의 오디오가 버퍼에 있는 상태로 만든다. 앞 3바이트는 MP3 프레임 헤더와 ID3 없는 시작부를 흉내낸 값이다. */
    private void bufferHolds(long durationMs) {
        buffer.clip = new AudioClip(
                new byte[] {(byte) 0xFF, (byte) 0xF3, 0x40, 0},
                CLIP_CONTENT_TYPE,
                0L,
                durationMs,
                Duration.ofMillis(durationMs));
        buffer.availableMs = durationMs;
    }

    private CaptureAudioClipResult capture() {
        return service.capture(new CaptureAudioClipCommand(SESSION_ID));
    }

    @Test
    void 충분히_쌓였으면_전사해서_텍스트를_돌려준다() {
        bufferHolds(Duration.ofMinutes(5).toMillis());

        CaptureAudioClipResult result = capture();

        assertTrue(result.transcribed());
        assertEquals(transcription.transcript, result.transcript());
        assertEquals(Duration.ofMinutes(5).toMillis(), result.availableMs());
        assertEquals(1, transcription.calls);
    }

    @Test
    void 설정된_창_길이로_버퍼에서_떠낸다() {
        bufferHolds(Duration.ofMinutes(5).toMillis());

        capture();

        assertEquals(WINDOW, buffer.requestedWindow);
    }

    @Test
    void 최소_길이_미만이면_전사하지_않고_가용량을_보고한다() {
        bufferHolds(30_000);

        CaptureAudioClipResult result = capture();

        // 수업 시작 직후 등. 전사 비용을 쓰지 않고 상위가 팁·쿨타임을 건너뛰게 한다.
        assertFalse(result.transcribed());
        assertEquals(30_000, result.availableMs());
        assertEquals(0, transcription.calls);
    }

    @Test
    void 최소_길이_경계값은_전사한다() {
        bufferHolds(MIN_TRANSCRIBABLE.toMillis());

        assertTrue(capture().transcribed());
    }

    @Test
    void 버퍼가_비어_있으면_전사하지_않는다() {
        buffer.clip = null;
        buffer.availableMs = 0;

        CaptureAudioClipResult result = capture();

        assertFalse(result.transcribed());
        assertEquals(0, transcription.calls);
    }

    @Test
    void 실제_확보된_길이를_결과에_담는다() {
        // 창은 5분이지만 3분만 쌓였다면 3분이 보고돼야 한다(팁 판정 근거).
        buffer.availableMs = Duration.ofMinutes(5).toMillis();
        buffer.clip = new AudioClip(new byte[10], CLIP_CONTENT_TYPE, 0L, 180_000L, Duration.ofMinutes(3));

        assertEquals(180_000, capture().availableMs());
    }

    @Test
    void 전사에는_클립이_알려준_형식을_그대로_넘긴다() {
        bufferHolds(Duration.ofMinutes(2).toMillis());

        capture();

        // 형식을 아는 쪽은 인코더뿐이다. 서비스가 MIME 타입을 자체 판단하면 인코더 교체 때 조용히 어긋난다.
        assertEquals(CLIP_CONTENT_TYPE, transcription.contentType);
        assertTrue(transcription.consumedBytes > 0);
    }

    @Test
    void 전사가_실패해도_예외가_전파되고_버퍼는_남는다() {
        bufferHolds(Duration.ofMinutes(2).toMillis());
        transcription.failure = new IllegalStateException("whisper down");

        assertThrows(AudioClipTranscriptionFailedException.class, this::capture);

        assertNotNull(buffer.clip, "다음 트리거가 같은 구간을 다시 시도할 수 있어야 한다");
        assertEquals(0, buffer.releaseCalls, "전사가 버퍼를 반납하지는 않는다(세션 종료 소관)");
    }

    private static final class FakeBuffer implements InstructorAudioBufferPort {
        private AudioClip clip;
        private long availableMs;
        private int releaseCalls;
        private Duration requestedWindow;

        @Override
        public Optional<AudioClip> snapshot(long sessionId, Duration window) {
            requestedWindow = window;
            return Optional.ofNullable(clip);
        }

        @Override
        public long availableMs(long sessionId) {
            return availableMs;
        }

        @Override
        public void release(long sessionId) {
            releaseCalls += 1;
        }
    }

    private static final class FakeTranscriptionPort implements AudioTranscriptionPort {
        private final String transcript = "전사 텍스트";
        private RuntimeException failure;
        private int calls;
        private long consumedBytes;
        private String contentType;

        @Override
        public String transcribe(InputStream audio, String contentType) {
            calls += 1;
            this.contentType = contentType;
            if (failure != null) {
                throw failure;
            }
            try {
                consumedBytes = audio.transferTo(OutputStream.nullOutputStream());
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return transcript;
        }
    }
}
