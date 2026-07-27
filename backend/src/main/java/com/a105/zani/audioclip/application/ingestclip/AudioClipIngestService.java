package com.a105.zani.audioclip.application.ingestclip;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
import com.a105.zani.common.error.BusinessException;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;

/**
 * 강사 클라이언트가 올린 클립 업로드와 실패 보고를 처리한다.
 *
 * <p>오디오는 메모리 스트림으로 전사 포트에 그대로 흘려보내며 디스크·DB 어디에도 저장하지 않는다 — 전사가 실패해도 상태만 FAILED 로 남기고 바이트는 폐기된다. 요청 상태는 Redis 에만 있고 DB
 * 는 멤버십 단건 읽기뿐이라 트랜잭션을 사용하지 않는다(전사 호출이 트랜잭션 안에서 커넥션을 점유하지 않도록).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudioClipIngestService implements UploadAudioClipUseCase, ReportAudioClipFailureUseCase {

    /** 업로드 상한. 32kbps Opus 기준 5분(약 1.2MB)의 여유 배수로, 버그난 클라이언트의 대용량 덤프를 차단한다. */
    static final long MAX_CLIP_BYTES = 8L * 1024 * 1024;

    /** 업로드할 가치가 있는 최소 캡처 시간. FE 의 MIN_UPLOADABLE_MS 와 함께 바뀌어야 한다. */
    static final Duration MIN_UPLOADABLE = Duration.ofMinutes(1);

    private static final String SUPPORTED_CONTENT_TYPE_PREFIX = "audio/webm";

    private final SessionParticipantRepository participantRepository;
    private final AudioClipRequestStorePort requestStore;
    private final AudioTranscriptionPort transcriptionPort;
    private final Clock clock;

    @Override
    public UploadAudioClipResult upload(UploadAudioClipCommand command) {
        requireInstructor(command.sessionId(), command.userId());
        AudioClipRequest request = findSessionRequest(command.sessionId(), command.clipId());

        // 재시도 중복 업로드는 멱등 처리한다. 이미 전사까지 끝났으므로 다시 전사하지 않는다.
        if (request.status() == AudioClipRequestStatus.UPLOADED) {
            return new UploadAudioClipResult(request.clipId(), request.status(), true);
        }
        if (request.isResolved()) {
            throw new AudioClipRequestAlreadyResolvedException();
        }
        if (request.isExpired(clock.instant())) {
            throw new AudioClipRequestExpiredException();
        }
        validateAudio(command);

        String transcript = transcribeDiscardingBytes(command, request);

        request.completeUpload(transcript);
        requestStore.save(request);
        log.info(
                "Audio clip {} transcribed for session {} ({} bytes, captured {}ms)",
                request.clipId(),
                request.sessionId(),
                command.sizeBytes(),
                command.meta().durationMs());
        return new UploadAudioClipResult(request.clipId(), request.status(), false);
    }

    @Override
    public void report(ReportAudioClipFailureCommand command) {
        requireInstructor(command.sessionId(), command.userId());
        AudioClipRequest request = findSessionRequest(command.sessionId(), command.clipId());

        if (request.isResolved()) {
            throw new AudioClipRequestAlreadyResolvedException();
        }
        // 만료 이후의 실패 보고도 수용한다 — "왜 안 왔는지"는 늦게라도 운영 신호로 가치가 있다.
        request.fail(command.reason());
        requestStore.save(request);
        log.info(
                "Audio clip {} failed for session {}: {} (available {}ms)",
                request.clipId(),
                request.sessionId(),
                command.reason(),
                command.availableMs());
    }

    /** 멤버가 아니거나 강사가 아니면 같은 403 — 비강사에게 요청 존재 여부를 노출하지 않는다. */
    private void requireInstructor(Long sessionId, Long userId) {
        SessionParticipant participant = participantRepository
                .findBySessionIdAndUserId(sessionId, userId)
                .orElseThrow(NotSessionInstructorException::new);
        if (participant.role() != SessionParticipantRole.INSTRUCTOR) {
            throw new NotSessionInstructorException();
        }
    }

    /** 다른 세션의 clipId 는 존재 자체를 노출하지 않고 404 로 처리한다. */
    private AudioClipRequest findSessionRequest(Long sessionId, Long clipId) {
        AudioClipRequest request = requestStore.find(clipId).orElseThrow(AudioClipRequestNotFoundException::new);
        if (!request.sessionId().equals(sessionId)) {
            throw new AudioClipRequestNotFoundException();
        }
        return request;
    }

    private void validateAudio(UploadAudioClipCommand command) {
        String contentType = command.contentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith(SUPPORTED_CONTENT_TYPE_PREFIX)) {
            throw new UnsupportedAudioFormatException();
        }
        if (command.sizeBytes() > MAX_CLIP_BYTES) {
            throw new AudioClipTooLargeException();
        }
        if (command.meta().durationMs() < MIN_UPLOADABLE.toMillis()) {
            throw new InsufficientAudioDurationException();
        }
    }

    /**
     * 전사에 실패하면 요청을 FAILED(TRANSCRIPTION_FAILED)로 기록한 뒤 예외를 그대로 전파한다. 어느 경로로 끝나든 오디오 바이트는 이 메서드를 벗어나며 참조가 사라진다(저장 경로 자체가
     * 없다).
     */
    private String transcribeDiscardingBytes(UploadAudioClipCommand command, AudioClipRequest request) {
        try {
            return transcriptionPort.transcribe(command.audio(), command.contentType());
        } catch (RuntimeException exception) {
            request.fail(AudioClipFailureReason.TRANSCRIPTION_FAILED);
            requestStore.save(request);
            if (exception instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new AudioClipTranscriptionFailedException(exception);
        }
    }
}
