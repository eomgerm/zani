package com.a105.zani.session.infrastructure.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.a105.zani.session.application.end.ExpireDueSessionsUseCase;

/**
 * 최대 수업 시간(3시간)을 넘긴 세션을 주기적으로 종료한다. 판단·종료 로직은 유스케이스가 갖고, 이 어댑터는 주기 실행만 담당한다. 스케줄러가 멈춘 동안 밀린 세션도 다음 실행에서 한꺼번에 정리된다(시각 기준
 * 판정).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionExpiryScheduler {

    private final ExpireDueSessionsUseCase expireDueSessionsUseCase;

    @Scheduled(fixedDelayString = "${session.expiry-sweep-delay:PT1M}")
    public void sweep() {
        try {
            expireDueSessionsUseCase.expireDueSessions();
        } catch (RuntimeException exception) {
            log.warn("Session expiry sweep failed: {}", exception.getMessage());
        }
    }
}
