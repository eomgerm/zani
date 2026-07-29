package com.a105.zani.audioclip.infrastructure.transcription;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.a105.zani.audioclip.application.exception.AudioClipTranscriptionFailedException;
import com.a105.zani.audioclip.application.port.AudioTranscriptionPort;

/**
 * GMS 크레딧을 쓰지 않고 개발·테스트할 때 쓰는 전사 어댑터. 스트림을 끝까지 소비해 즉시 폐기하고(디스크 미기록) 고정 텍스트를 반환한다.
 *
 * <p>{@code gms.mock-enabled} 스위치로 실제 어댑터({@code GmsAudioTranscriptionAdapter})와 갈라진다. 둘 다 무조건 등록되면
 * {@link AudioTranscriptionPort} 빈이 둘이라 기동이 실패하므로 조건이 서로 배타적이어야 한다. 운영에서 이 어댑터가 뜨는 사고는 {@code GmsMockProfileGuard} 가 기동
 * 시점에 막는다. (S15P11A105-203)
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "gms", name = "mock-enabled", havingValue = "true", matchIfMissing = true)
public class StubAudioTranscriptionAdapter implements AudioTranscriptionPort {

    static final String PLACEHOLDER_TRANSCRIPT = "[전사 미연동] Whisper 전사 서비스 연동 전의 임시 텍스트입니다.";

    @Override
    public String transcribe(InputStream audio, String contentType) {
        try {
            long discardedBytes = audio.transferTo(OutputStream.nullOutputStream());
            log.info("Stub transcription consumed and discarded {} bytes ({})", discardedBytes, contentType);
            return PLACEHOLDER_TRANSCRIPT;
        } catch (IOException exception) {
            throw new AudioClipTranscriptionFailedException(exception);
        }
    }
}
