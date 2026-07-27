package com.a105.zani.audioclip.infrastructure.dispatch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.a105.zani.audioclip.application.port.AudioClipDispatchPort;
import com.a105.zani.audioclip.domain.model.AudioClipRequest;

/** SSE 디스패치 어댑터가 붙기 전까지 로그만 남기는 임시 구현. 요청 자체는 저장소의 PENDING 기록으로 남으므로, 실 어댑터가 붙으면 재구독 리플레이 경로로 그대로 회복된다. */
@Slf4j
@Component
public class LoggingAudioClipDispatcher implements AudioClipDispatchPort {

    @Override
    public void dispatch(AudioClipRequest request) {
        log.info(
                "Audio clip {} for session {} is pending dispatch (expires {})",
                request.clipId(),
                request.sessionId(),
                request.expiresAt());
    }
}
