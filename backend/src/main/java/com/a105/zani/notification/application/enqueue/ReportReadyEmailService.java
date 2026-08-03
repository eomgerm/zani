package com.a105.zani.notification.application.enqueue;

import java.time.Clock;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.notification.application.port.NewNotification;
import com.a105.zani.notification.application.port.NotificationOutboxPort;
import com.a105.zani.notification.application.port.ReadyReportQueryPort;
import com.a105.zani.notification.application.port.ReportRecipient;
import com.a105.zani.notification.application.port.ReportRecipientQueryPort;
import com.a105.zani.notification.domain.model.NotificationType;

/**
 * 리포트가 공개 완료된 세션을 폴링해 학생 수신자마다 알림 한 건을 outbox 에 등록한다(producer). 리포트를 공개하는 쪽(AI 파이프라인)이 이 모듈을 몰라도 되도록, 오직
 * {@code session_reports.published_at} 만 읽는다.
 *
 * <p>등록은 dedup_key(세션·수신자·유형)로 멱등이다 — 같은 세션이 다음 폴링에서 다시 걸려도, 재시작으로 이 메서드가 다시 돌아도, 학생당 알림은 정확히 한 건이다. 실제 발송·재시도는 outbox
 * consumer 가 맡는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportReadyEmailService implements ReportReadyEmailUseCase {

    /** 한 번의 폴링에서 살펴볼 세션 수 상한. 밀린 세션은 다음 주기에서 이어 처리된다. */
    private static final int DISCOVER_LIMIT = 50;

    private final ReadyReportQueryPort readyReportQueryPort;
    private final ReportRecipientQueryPort reportRecipientQueryPort;
    private final NotificationOutboxPort outboxPort;
    private final Clock clock;

    @Override
    public void enqueueReadyReports() {
        List<Long> sessionIds = readyReportQueryPort.findReadyReportSessionsWithoutNotification(DISCOVER_LIMIT);
        for (Long sessionId : sessionIds) {
            enqueueForSession(sessionId);
        }
    }

    private void enqueueForSession(Long sessionId) {
        // 한 세션의 수신자 전체를 모아 한 번에(한 트랜잭션) 등록한다. 개별 등록 중 중단되면 일부만 남아 세션이 재발견에서 빠지고
        // 나머지 학생이 영영 누락되므로, 원자적으로 넣어 중단 시 통째로 롤백·재발견되게 한다.
        List<NewNotification> notifications = reportRecipientQueryPort.findRecipients(sessionId).stream()
                .map(recipient -> toNotification(sessionId, recipient))
                .toList();
        int enqueued = outboxPort.enqueueAll(notifications, clock.instant());
        if (enqueued > 0) {
            log.debug("리포트 준비 알림을 등록했습니다. sessionId={}, 신규={}건", sessionId, enqueued);
        }
    }

    private NewNotification toNotification(Long sessionId, ReportRecipient recipient) {
        String type = NotificationType.REPORT_READY.name();
        String dedupKey = sessionId + ":" + recipient.memberId() + ":" + type;
        return new NewNotification(
                sessionId, recipient.memberId(), recipient.email(), recipient.displayName(), type, dedupKey);
    }
}
