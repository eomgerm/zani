package com.a105.zani.notification.infrastructure.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.a105.zani.notification.application.consume.ConsumeNotificationOutboxUseCase;
import com.a105.zani.notification.application.enqueue.ReportReadyEmailUseCase;

/**
 * 주기적으로 (1) 리포트가 준비된 세션의 알림을 outbox 에 등록하고 (2) 밀린 알림을 소비해 이메일을 보낸다. 판단·발송 로직은 유스케이스가 갖고, 이 어댑터는 주기 실행과 예외 삼킴만 담당한다.
 *
 * <p>{@code notification.email.enabled=true} 일 때만 등록된다. 메일 설정이 없는 로컬·통합 테스트에서 기본 비활성이라, 발송 실패를 반복하거나 스케줄러 풀을 점유하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "notification.email.enabled", havingValue = "true")
public class NotificationScheduler {

    private final ReportReadyEmailUseCase reportReadyEmailUseCase;
    private final ConsumeNotificationOutboxUseCase consumeNotificationOutboxUseCase;

    @Scheduled(fixedDelayString = "${notification.email.relay-delay:PT10S}")
    public void run() {
        try {
            reportReadyEmailUseCase.enqueueReadyReports();
            consumeNotificationOutboxUseCase.consume();
        } catch (RuntimeException exception) {
            // 스케줄러는 예외를 삼켜 다음 주기를 살린다. 여기서 스택트레이스를 남기지 않으면 원인이 사라진다.
            log.warn("Notification relay pass failed", exception);
        }
    }
}
