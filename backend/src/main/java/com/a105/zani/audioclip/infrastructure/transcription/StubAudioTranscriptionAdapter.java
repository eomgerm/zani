package com.a105.zani.audioclip.infrastructure.transcription;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.a105.zani.audioclip.application.exception.AudioClipTranscriptionFailedException;
import com.a105.zani.audioclip.application.port.AudioTranscriptionPort;

/** Whisper 연동 전까지 쓰는 임시 전사 어댑터. 스트림을 끝까지 소비해 즉시 폐기하고(디스크 미기록) 고정 텍스트를 반환한다. 실제 전사 서비스로 교체할 때 이 클래스만 대체하면 된다. */
@Slf4j
@Component
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
