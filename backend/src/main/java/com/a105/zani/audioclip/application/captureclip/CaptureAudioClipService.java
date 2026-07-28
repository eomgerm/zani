package com.a105.zani.audioclip.application.captureclip;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.audioclip.application.exception.AudioClipTranscriptionFailedException;
import com.a105.zani.audioclip.application.port.AudioClip;
import com.a105.zani.audioclip.application.port.AudioTranscriptionPort;
import com.a105.zani.audioclip.application.port.InstructorAudioBufferPort;
import com.a105.zani.audioclip.infrastructure.config.AudioClipProperties;
import com.a105.zani.common.error.BusinessException;

/**
 * 코칭 트리거 시점의 강사 최근 발화를 전사한다.
 *
 * <p>오디오는 이미 서버 메모리(링버퍼)에 있으므로 업로드도 파일 변환도 없다. 버퍼가 16kHz WAV 로 떠서 주고, 전사 포트에 넘긴 뒤 호출이 끝나면 바이트 참조가 사라진다(저장 경로 자체가 없다).
 *
 * <p>확보량이 최소 길이에 못 미치면 전사를 시도하지 않는다. 수업 시작 직후처럼 맥락이 부족한 구간에 전사 비용을 쓰지 않고, 상위가 팁 생성과 쿨타임을 건너뛸 수 있게 사유를 함께 돌려준다.
 */
@Slf4j
@Service
public class CaptureAudioClipService implements CaptureAudioClipUseCase {

    private final InstructorAudioBufferPort buffer;
    private final AudioTranscriptionPort transcriptionPort;
    private final Duration window;
    private final Duration minTranscribable;

    public CaptureAudioClipService(
            InstructorAudioBufferPort buffer,
            AudioTranscriptionPort transcriptionPort,
            AudioClipProperties properties) {
        this.buffer = buffer;
        this.transcriptionPort = transcriptionPort;
        this.window = properties.window();
        this.minTranscribable = properties.minTranscribable();
    }

    @Override
    public CaptureAudioClipResult capture(CaptureAudioClipCommand command) {
        long sessionId = command.sessionId();
        long availableMs = buffer.availableMs(sessionId);
        if (availableMs < minTranscribable.toMillis()) {
            log.info("Audio clip skipped for session {}: only {}ms buffered", sessionId, availableMs);
            return CaptureAudioClipResult.skipped(availableMs);
        }

        Optional<AudioClip> clip = buffer.snapshot(sessionId, window);
        if (clip.isEmpty()) {
            return CaptureAudioClipResult.skipped(availableMs);
        }

        AudioClip audio = clip.get();
        String transcript = transcribeDiscardingBytes(audio, sessionId);
        log.info(
                "Audio clip transcribed for session {} ({}ms, {} bytes, {})",
                sessionId,
                audio.actual().toMillis(),
                audio.audio().length,
                audio.contentType());
        return CaptureAudioClipResult.of(transcript, audio.actual().toMillis());
    }

    /** 전사 실패는 그대로 전파한다. 어느 경로로 끝나든 오디오 바이트는 이 메서드를 벗어나며 참조가 사라진다. 버퍼는 비우지 않는다 — 다음 트리거가 같은 구간을 다시 시도할 수 있어야 한다. */
    private String transcribeDiscardingBytes(AudioClip audio, long sessionId) {
        try {
            return transcriptionPort.transcribe(new ByteArrayInputStream(audio.audio()), audio.contentType());
        } catch (RuntimeException exception) {
            log.warn("Audio clip transcription failed for session {}", sessionId);
            if (exception instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new AudioClipTranscriptionFailedException(exception);
        }
    }
}
