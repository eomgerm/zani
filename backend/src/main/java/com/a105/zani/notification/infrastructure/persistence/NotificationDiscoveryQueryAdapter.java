package com.a105.zani.notification.infrastructure.persistence;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.notification.application.port.ReadyReportQueryPort;
import com.a105.zani.notification.application.port.ReportRecipient;
import com.a105.zani.notification.application.port.ReportRecipientQueryPort;
import com.a105.zani.notification.domain.model.NotificationType;
import com.a105.zani.notification.infrastructure.persistence.repository.NotificationDiscoveryJpaRepository;

/** producer 의 교차 모듈 읽기를 두 포트로 노출한다. 읽기 전용이라 다른 모듈의 테이블에 쓰지 않는다. */
@Component
@RequiredArgsConstructor
public class NotificationDiscoveryQueryAdapter implements ReadyReportQueryPort, ReportRecipientQueryPort {

    private final NotificationDiscoveryJpaRepository discoveryRepository;

    @Override
    @Transactional(readOnly = true)
    public List<Long> findReadyReportSessionsWithoutNotification(int limit) {
        return discoveryRepository.findReadyReportSessionsWithoutNotification(
                NotificationType.REPORT_READY.name(), limit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReportRecipient> findRecipients(Long sessionId) {
        return discoveryRepository.findRecipients(sessionId).stream()
                .map(row -> new ReportRecipient(row.getMemberId(), row.getEmail(), row.getDisplayName()))
                .toList();
    }
}
