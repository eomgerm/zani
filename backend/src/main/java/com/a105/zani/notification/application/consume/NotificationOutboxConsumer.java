package com.a105.zani.notification.application.consume;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.notification.application.port.EmailSenderPort;
import com.a105.zani.notification.application.port.NotificationOutboxPort;
import com.a105.zani.notification.application.port.PendingNotification;

/**
 * outbox 의 PENDING 알림을 하나씩 선점해 이메일을 보낸다(consumer).
 *
 * <p>멱등: claim 으로 행을 선점하므로 다중 인스턴스·중복 스케줄에서도 한 건은 한 번만 발송된다. 이미 SENT 인 행은 다시 뽑히지 않는다. 실패 기록: 발송 예외는 시도 횟수와 마지막 사유로 남기고
 * 백오프 재시도하며, 상한을 넘으면 FAILED 로 접어 무한 재시도를 막는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationOutboxConsumer implements ConsumeNotificationOutboxUseCase {

    private static final int RELAY_LIMIT = 20;
    private static final int MAX_ATTEMPTS = 10;
    private static final Duration LEASE = Duration.ofMinutes(5);

    private final NotificationOutboxPort outboxPort;
    private final EmailSenderPort emailSenderPort;
    private final ReportReadyEmailComposer composer;
    private final Clock clock;

    @Override
    public void consume() {
        Instant now = clock.instant();
        outboxPort.requeueExpiredClaims(now.minus(LEASE), now);
        for (PendingNotification notification : outboxPort.fetchDue(RELAY_LIMIT, now)) {
            if (!outboxPort.claim(notification.id(), clock.instant())) {
                continue; // 다른 consumer 가 먼저 가져갔다.
            }
            send(notification);
        }
    }

    private void send(PendingNotification notification) {
        try {
            emailSenderPort.send(composer.compose(notification));
            outboxPort.markSent(notification.id(), clock.instant());
        } catch (RuntimeException exception) {
            recordFailure(notification, exception);
        }
    }

    private void recordFailure(PendingNotification notification, RuntimeException exception) {
        String reason = exception.getMessage();
        if (notification.attemptCount() >= MAX_ATTEMPTS) {
            log.warn(
                    "알림 이메일을 재시도 상한까지 실패했습니다. id={}, sessionId={}, memberId={}",
                    notification.id(),
                    notification.sessionId(),
                    notification.memberId(),
                    exception);
            outboxPort.markFailed(notification.id(), reason);
            return;
        }
        log.warn(
                "알림 이메일 발송 실패, 재시도합니다. id={}, sessionId={}, memberId={}",
                notification.id(),
                notification.sessionId(),
                notification.memberId(),
                exception);
        outboxPort.markRetry(notification.id(), reason, clock.instant().plus(backoff(notification.attemptCount())));
    }

    /** 지수 백오프: 1분에서 시작해 최대 1시간까지 벌린다. */
    private static Duration backoff(int attemptCount) {
        long seconds = Math.min(3600L, 60L * (1L << Math.min(attemptCount, 6)));
        return Duration.ofSeconds(seconds);
    }
}
