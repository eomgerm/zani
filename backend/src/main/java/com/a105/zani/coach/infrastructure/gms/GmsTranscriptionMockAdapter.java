package com.a105.zani.coach.infrastructure.gms;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.a105.zani.coach.application.port.AudioClip;
import com.a105.zani.coach.application.port.GmsTranscriptionPort;
import com.a105.zani.coach.application.port.TranscriptResult;

/**
 * mock 모드용 전사. 실제 GMS 를 호출하지 않고 고정 텍스트를 반환한다. 트리거당 크레딧이 소모되므로 개발·테스트에서는 이 구현을 쓴다. 운영 프로파일에서는
 * {@link GmsMockProfileGuard} 가 mock 사용을 차단한다.
 */
@Component
@ConditionalOnProperty(prefix = "gms", name = "mock-enabled", havingValue = "true", matchIfMissing = true)
public class GmsTranscriptionMockAdapter implements GmsTranscriptionPort {

    private static final Logger log = LoggerFactory.getLogger(GmsTranscriptionMockAdapter.class);
    static final String MOCK_TRANSCRIPT = "이번 구간에서는 예시를 들어 핵심 개념을 설명했습니다.";

    @Override
    public Optional<TranscriptResult> transcribe(AudioClip clip) {
        if (clip == null || clip.mp3() == null || clip.mp3().length == 0) {
            return Optional.empty();
        }
        log.info("GMS mock mode: returning fixed transcript without a real call (bytes={})", clip.mp3().length);
        return Optional.of(new TranscriptResult(MOCK_TRANSCRIPT, 0L));
    }
}
