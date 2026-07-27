package com.a105.zani.audioclip.application.ingestclip;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.a105.zani.audioclip.application.exception.AudioClipRequestAlreadyResolvedException;
import com.a105.zani.audioclip.application.exception.AudioClipRequestExpiredException;
import com.a105.zani.audioclip.application.exception.AudioClipRequestNotFoundException;
import com.a105.zani.audioclip.application.exception.AudioClipTooLargeException;
import com.a105.zani.audioclip.application.exception.AudioClipTranscriptionFailedException;
import com.a105.zani.audioclip.application.exception.InsufficientAudioDurationException;
import com.a105.zani.audioclip.application.exception.NotSessionInstructorException;
import com.a105.zani.audioclip.application.exception.UnsupportedAudioFormatException;
import com.a105.zani.audioclip.application.port.AudioClipRequestStorePort;
import com.a105.zani.audioclip.application.port.AudioTranscriptionPort;
import com.a105.zani.audioclip.domain.model.AudioClipFailureReason;
import com.a105.zani.audioclip.domain.model.AudioClipRequest;
import com.a105.zani.audioclip.domain.model.AudioClipRequestStatus;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioClipIngestServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long OTHER_SESSION_ID = 200L;
    private static final long INSTRUCTOR_USER = 7L;
    private static final long STUDENT_USER = 8L;
    private static final long CLIP_ID = 1L;
    private static final Instant T0 = Instant.parse("2026-07-27T00:00:00Z");
    private static final String WEBM = "audio/webm;codecs=opus";
    private static final long VALID_DURATION_MS = Duration.ofMinutes(3).toMillis();

    private final FakeParticipantRepository participantRepository = new FakeParticipantRepository();
    private final FakeAudioClipRequestStore requestStore = new FakeAudioClipRequestStore();
    private final FakeTranscriptionPort transcriptionPort = new FakeTranscriptionPort();

    private AudioClipIngestService service;

    @BeforeEach
    void setUp() {
        // 요청 생성(T0) 후 30초 지난 시점 — 만료(60초) 전이다.
        service = new AudioClipIngestService(
                participantRepository,
                requestStore,
                transcriptionPort,
                Clock.fixed(T0.plusSeconds(30), ZoneOffset.UTC));
        participantRepository.put(SESSION_ID, INSTRUCTOR_USER, SessionParticipantRole.INSTRUCTOR);
        participantRepository.put(SESSION_ID, STUDENT_USER, SessionParticipantRole.STUDENT);
        requestStore.save(AudioClipRequest.create(CLIP_ID, SESSION_ID, T0));
    }

    // ---- 업로드 ----

    @Test
    void 세션_멤버가_아니면_403() {
        assertThrows(NotSessionInstructorException.class, () -> service.upload(uploadCommand(999L)));
    }

    @Test
    void 학생이면_403() {
        assertThrows(NotSessionInstructorException.class, () -> service.upload(uploadCommand(STUDENT_USER)));
    }

    @Test
    void 없는_클립_요청이면_404() {
        assertThrows(
                AudioClipRequestNotFoundException.class,
                () -> service.upload(uploadCommand(INSTRUCTOR_USER, 404L, WEBM, 1_000, VALID_DURATION_MS)));
    }

    @Test
    void 다른_세션의_클립_요청이면_존재를_노출하지_않고_404() {
        requestStore.save(AudioClipRequest.create(2L, OTHER_SESSION_ID, T0));

        assertThrows(
                AudioClipRequestNotFoundException.class,
                () -> service.upload(uploadCommand(INSTRUCTOR_USER, 2L, WEBM, 1_000, VALID_DURATION_MS)));
    }

    @Test
    void 만료된_요청이면_409() {
        service = new AudioClipIngestService(
                participantRepository,
                requestStore,
                transcriptionPort,
                Clock.fixed(T0.plus(AudioClipRequest.REQUEST_TTL), ZoneOffset.UTC));

        assertThrows(AudioClipRequestExpiredException.class, () -> service.upload(uploadCommand(INSTRUCTOR_USER)));
    }

    @Test
    void 실패로_종결된_요청에_업로드하면_409() {
        requestStore.byId.get(CLIP_ID).fail(AudioClipFailureReason.UPLOAD_FAILED);

        assertThrows(
                AudioClipRequestAlreadyResolvedException.class, () -> service.upload(uploadCommand(INSTRUCTOR_USER)));
    }

    @Test
    void 업로드_완료된_요청의_재업로드는_멱등이고_전사를_다시_하지_않는다() {
        UploadAudioClipResult first = service.upload(uploadCommand(INSTRUCTOR_USER));
        UploadAudioClipResult second = service.upload(uploadCommand(INSTRUCTOR_USER));

        assertFalse(first.alreadyUploaded());
        assertTrue(second.alreadyUploaded());
        assertEquals(AudioClipRequestStatus.UPLOADED, second.status());
        assertEquals(1, transcriptionPort.calls);
    }

    @Test
    void webm이_아니면_415() {
        assertThrows(
                UnsupportedAudioFormatException.class,
                () -> service.upload(uploadCommand(INSTRUCTOR_USER, CLIP_ID, "audio/mpeg", 1_000, VALID_DURATION_MS)));
    }

    @Test
    void 크기_상한을_넘으면_413() {
        long tooLarge = AudioClipIngestService.MAX_CLIP_BYTES + 1;

        assertThrows(
                AudioClipTooLargeException.class,
                () -> service.upload(uploadCommand(INSTRUCTOR_USER, CLIP_ID, WEBM, tooLarge, VALID_DURATION_MS)));
    }

    @Test
    void 캡처_시간이_1분_미만이면_400() {
        long tooShort = AudioClipIngestService.MIN_UPLOADABLE.toMillis() - 1;

        assertThrows(
                InsufficientAudioDurationException.class,
                () -> service.upload(uploadCommand(INSTRUCTOR_USER, CLIP_ID, WEBM, 1_000, tooShort)));
    }

    @Test
    void 전사가_실패해도_요청을_FAILED로_기록하고_예외를_전파한다() {
        transcriptionPort.failure = new IllegalStateException("whisper down");

        assertThrows(AudioClipTranscriptionFailedException.class, () -> service.upload(uploadCommand(INSTRUCTOR_USER)));

        AudioClipRequest request = requestStore.byId.get(CLIP_ID);
        assertEquals(AudioClipRequestStatus.FAILED, request.status());
        assertEquals(AudioClipFailureReason.TRANSCRIPTION_FAILED, request.failureReason());
        assertTrue(requestStore.saves >= 2, "실패 상태가 저장소에 반영돼야 한다");
    }

    @Test
    void 정상_업로드면_스트림을_소비하고_전사_텍스트만_남긴다() {
        UploadAudioClipResult result = service.upload(uploadCommand(INSTRUCTOR_USER));

        assertEquals(AudioClipRequestStatus.UPLOADED, result.status());
        AudioClipRequest request = requestStore.byId.get(CLIP_ID);
        assertEquals(transcriptionPort.transcript, request.transcriptText());
        assertTrue(transcriptionPort.consumedBytes > 0, "오디오 스트림이 전사 포트로 흘러가야 한다");
    }

    // ---- 실패 보고 ----

    @Test
    void 실패_보고는_사유를_기록한다() {
        service.report(new ReportAudioClipFailureCommand(
                SESSION_ID, CLIP_ID, INSTRUCTOR_USER, AudioClipFailureReason.INSUFFICIENT_AUDIO, 45_000L));

        AudioClipRequest request = requestStore.byId.get(CLIP_ID);
        assertEquals(AudioClipRequestStatus.FAILED, request.status());
        assertEquals(AudioClipFailureReason.INSUFFICIENT_AUDIO, request.failureReason());
    }

    @Test
    void 만료_이후의_실패_보고도_수용한다() {
        service = new AudioClipIngestService(
                participantRepository,
                requestStore,
                transcriptionPort,
                Clock.fixed(T0.plus(AudioClipRequest.REQUEST_TTL).plusSeconds(10), ZoneOffset.UTC));

        service.report(new ReportAudioClipFailureCommand(
                SESSION_ID, CLIP_ID, INSTRUCTOR_USER, AudioClipFailureReason.UPLOAD_FAILED, 120_000L));

        assertEquals(
                AudioClipRequestStatus.FAILED, requestStore.byId.get(CLIP_ID).status());
    }

    @Test
    void 이미_처리된_요청의_실패_보고는_409() {
        service.upload(uploadCommand(INSTRUCTOR_USER));

        assertThrows(
                AudioClipRequestAlreadyResolvedException.class,
                () -> service.report(new ReportAudioClipFailureCommand(
                        SESSION_ID, CLIP_ID, INSTRUCTOR_USER, AudioClipFailureReason.UPLOAD_FAILED, 0L)));
    }

    @Test
    void 학생의_실패_보고는_403() {
        assertThrows(
                NotSessionInstructorException.class,
                () -> service.report(new ReportAudioClipFailureCommand(
                        SESSION_ID, CLIP_ID, STUDENT_USER, AudioClipFailureReason.MICROPHONE_OFF, 0L)));
    }

    // ---- 헬퍼·페이크 ----

    private UploadAudioClipCommand uploadCommand(long userId) {
        return uploadCommand(userId, CLIP_ID, WEBM, 1_000, VALID_DURATION_MS);
    }

    private UploadAudioClipCommand uploadCommand(
            long userId, long clipId, String contentType, long sizeBytes, long durationMs) {
        Instant to = T0.plusSeconds(30);
        Instant from = to.minusMillis(durationMs);
        UploadedClipMeta meta = new UploadedClipMeta(from, to, durationMs, List.of(new CapturedSegment(from, to)));
        return new UploadAudioClipCommand(
                SESSION_ID, clipId, userId, contentType, sizeBytes, new ByteArrayInputStream(new byte[64]), meta);
    }

    private static class FakeParticipantRepository implements SessionParticipantRepository {
        private final Map<String, SessionParticipant> byKey = new HashMap<>();
        private long nextId = 1;

        void put(long sessionId, long userId, SessionParticipantRole role) {
            byKey.put(
                    sessionId + ":" + userId,
                    SessionParticipant.reconstitute(nextId++, sessionId, userId, role, T0, T0));
        }

        @Override
        public Optional<SessionParticipant> findBySessionIdAndUserId(Long sessionId, Long userId) {
            return Optional.ofNullable(byKey.get(sessionId + ":" + userId));
        }

        @Override
        public SessionParticipant save(SessionParticipant sessionParticipant) {
            return sessionParticipant;
        }
    }

    private static class FakeAudioClipRequestStore implements AudioClipRequestStorePort {
        final Map<Long, AudioClipRequest> byId = new HashMap<>();
        int saves;

        @Override
        public void save(AudioClipRequest request) {
            saves++;
            byId.put(request.clipId(), request);
        }

        @Override
        public Optional<AudioClipRequest> find(long clipId) {
            return Optional.ofNullable(byId.get(clipId));
        }

        @Override
        public List<AudioClipRequest> findPending(long sessionId) {
            return byId.values().stream()
                    .filter(request ->
                            request.sessionId() == sessionId && request.status() == AudioClipRequestStatus.PENDING)
                    .toList();
        }
    }

    private static class FakeTranscriptionPort implements AudioTranscriptionPort {
        String transcript = "전사 텍스트";
        RuntimeException failure;
        int calls;
        long consumedBytes = -1;

        @Override
        public String transcribe(InputStream audio, String contentType) {
            calls++;
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
