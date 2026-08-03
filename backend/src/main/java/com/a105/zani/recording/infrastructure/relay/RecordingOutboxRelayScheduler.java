package com.a105.zani.recording.infrastructure.relay;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.a105.zani.recording.application.orchestrate.RelayRecordingOutboxUseCase;

/** recording_outbox의 PENDING 행을 주기적으로 소비한다. 실패는 orchestrator가 시도 횟수로 관리하므로 여기서는 로그만 남긴다. */
@Slf4j
@Component
@ConditionalOnProperty(name = "recording.outbox-relay-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RecordingOutboxRelayScheduler {

    private final RelayRecordingOutboxUseCase relayRecordingOutboxUseCase;

    @Scheduled(fixedDelayString = "${recording.outbox-relay-delay:PT5S}")
    public void relay() {
        try {
            relayRecordingOutboxUseCase.relayPendingOutbox();
        } catch (RuntimeException exception) {
            log.warn("Recording outbox relay pass failed: {}", exception.getMessage());
        }
    }
}
