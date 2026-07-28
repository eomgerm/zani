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
import com.a105.zani.audioclip.application.port.AudioTranscriptionPort;
import com.a105.zani.audioclip.application.port.CapturedAudio;
import com.a105.zani.audioclip.application.port.InstructorAudioBufferPort;
import com.a105.zani.audioclip.domain.model.PcmAudioFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureAudioClipServiceTest {

    private static final long SESSION_ID = 100L;
    private static final Duration MIN_TRANSCRIBABLE = Duration.ofMinutes(1);
    private static final PcmAudioFormat FORMAT = PcmAudioFormat.liveKitDefault();

    private final FakeBuffer buffer = new FakeBuffer();
    private final FakeTranscriptionPort transcription = new FakeTranscriptionPort();

    private CaptureAudioClipService service;

    @BeforeEach
    void setUp() {
        service = new CaptureAudioClipService(
                buffer,
                transcription,
                new com.a105.zani.audioclip.infrastructure.config.AudioClipProperties(
                        null, MIN_TRANSCRIBABLE, null, null, null));
    }

    /** durationMs 만큼의 오디오가 버퍼에 있는 상태로 만든다. */
    private void bufferHolds(long durationMs) {
        int bytes = (int) FORMAT.bytesFor(Duration.ofMillis(durationMs));
        buffer.captured = new CapturedAudio(new byte[bytes], FORMAT, durationMs);
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
    void 최소_길이_미만이면_전사하지_않고_가용량을_보고한다() {
        bufferHolds(30_000);

        CaptureAudioClipResult result = capture();

        // 수업 시작 직후 등. 전사 비용을 쓰지 않고 상위가 팁 생성을 건너뛰게 한다.
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
        buffer.captured = null;
        buffer.availableMs = 0;

        CaptureAudioClipResult result = capture();

        assertFalse(result.transcribed());
        assertEquals(0, result.availableMs());
        assertEquals(0, transcription.calls);
    }

    @Test
    void 전사에_넘기는_바이트는_WAV_헤더가_붙은_디코딩_가능한_형식이다() {
        bufferHolds(Duration.ofMinutes(2).toMillis());

        capture();

        assertEquals("audio/wav", transcription.contentType);
        assertTrue(transcription.consumedBytes > 44, "PCM 앞에 WAV 헤더가 붙어야 한다");
        assertEquals("RIFF", new String(transcription.firstFourBytes, java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test
    void 전사가_실패해도_오디오는_폐기되고_예외가_전파된다() {
        bufferHolds(Duration.ofMinutes(2).toMillis());
        transcription.failure = new IllegalStateException("whisper down");

        assertThrows(AudioClipTranscriptionFailedException.class, this::capture);

        // 버퍼는 그대로 남는다 — 다음 트리거가 같은 구간을 다시 시도할 수 있어야 한다.
        assertTrue(buffer.captured != null);
    }

    @Test
    void capture는_버퍼를_비우지_않는다() {
        bufferHolds(Duration.ofMinutes(2).toMillis());

        capture();
        capture();

        assertEquals(2, transcription.calls);
        assertEquals(0, buffer.releaseCalls, "전사가 버퍼를 반납하지는 않는다(세션 종료 소관)");
    }

    private static final class FakeBuffer implements InstructorAudioBufferPort {
        private CapturedAudio captured;
        private long availableMs;
        private int releaseCalls;

        @Override
        public Optional<CapturedAudio> capture(long sessionId) {
            return Optional.ofNullable(captured);
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
        private byte[] firstFourBytes = new byte[4];

        @Override
        public String transcribe(InputStream audio, String contentType) {
            calls += 1;
            this.contentType = contentType;
            if (failure != null) {
                throw failure;
            }
            try {
                int read = audio.read(firstFourBytes);
                consumedBytes = read + audio.transferTo(OutputStream.nullOutputStream());
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return transcript;
        }
    }
}
