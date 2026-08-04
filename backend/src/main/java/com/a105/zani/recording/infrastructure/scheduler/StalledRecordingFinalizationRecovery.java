package com.a105.zani.recording.infrastructure.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.a105.zani.recording.application.recoverstalledfinalizations.RecoverStalledFinalizationsUseCase;

/**
 * 기동 직후 이전 백엔드 인스턴스가 남긴 {@code RUNNING} 병합 작업을 한 번만 회수한다.
 *
 * <p>현재 배포는 백엔드 단일 인스턴스다. 다중 인스턴스로 확장하면 인스턴스 소유권을 작업에 기록해 자기 작업만 회수하도록 좁혀야 한다. 지금도 worker {@code flock}과 DB fencing이 중복
 * 결과 기록을 차단한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "recording.finalization.dispatch",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class StalledRecordingFinalizationRecovery {

    private final RecoverStalledFinalizationsUseCase recoverStalledFinalizationsUseCase;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        try {
            recoverStalledFinalizationsUseCase.recover();
        } catch (RuntimeException failure) {
            // 복구 실패가 애플리케이션 기동을 막으면 기존 강의 기능까지 사용할 수 없으므로 기록 후 계속 기동한다.
            log.error("Could not requeue recording finalization jobs left by a stopped worker", failure);
        }
    }
}
