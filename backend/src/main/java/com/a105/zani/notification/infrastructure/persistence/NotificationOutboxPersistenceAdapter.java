package com.a105.zani.notification.infrastructure.persistence;

import java.time.Instant;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.notification.application.port.NewNotification;
import com.a105.zani.notification.application.port.NotificationOutboxPort;
import com.a105.zani.notification.application.port.PendingNotification;
import com.a105.zani.notification.infrastructure.persistence.entity.NotificationOutboxJpaEntity;
import com.a105.zani.notification.infrastructure.persistence.repository.NotificationOutboxJpaRepository;

/**
 * 알림 outbox 영속 어댑터. enqueue 는 INSERT IGNORE 로 dedup_key UNIQUE 충돌을 호출자 트랜잭션 오염 없이 흡수하고, claim/requeue 는 원자 UPDATE 로
 * consumer 의 중복 발송을 막는다.
 */
@Component
@RequiredArgsConstructor
public class NotificationOutboxPersistenceAdapter implements NotificationOutboxPort {

    private static final int MAX_ERROR_LENGTH = 500;

    private final NotificationOutboxJpaRepository outboxRepository;

    @Override
    @Transactional
    public int enqueueAll(List<NewNotification> notifications, Instant now) {
        // 한 세션의 수신자 전체를 한 트랜잭션에 넣는다. 도중 실패하면 함께 롤백돼, 세션이 다음 폴링에서 다시 발견돼 재시도된다.
        int inserted = 0;
        for (NewNotification notification : notifications) {
            inserted += outboxRepository.insertIgnore(
                    TsidGenerator.generate(),
                    notification.sessionId(),
                    notification.memberId(),
                    notification.email(),
                    notification.displayName(),
                    notification.type(),
                    notification.dedupKey(),
                    now);
        }
        return inserted;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PendingNotification> fetchDue(int limit, Instant now) {
        return outboxRepository
                .findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        NotificationOutboxJpaEntity.STATUS_PENDING, now, PageRequest.of(0, limit))
                .stream()
                .map(entity -> new PendingNotification(
                        entity.getId(),
                        entity.getSessionId(),
                        entity.getMemberId(),
                        entity.getEmail(),
                        entity.getDisplayName(),
                        entity.getAttemptCount()))
                .toList();
    }

    @Override
    @Transactional
    public boolean claim(Long id, Instant now) {
        return outboxRepository.claim(id, now) == 1;
    }

    @Override
    @Transactional
    public void requeueExpiredClaims(Instant cutoff, Instant now) {
        outboxRepository.requeueExpiredClaims(cutoff, now);
    }

    @Override
    @Transactional
    public void markSent(Long id, Instant now) {
        outboxRepository.findById(id).ifPresent(entity -> entity.markSent(now));
    }

    @Override
    @Transactional
    public void markRetry(Long id, String error, Instant nextAttemptAt) {
        outboxRepository.findById(id).ifPresent(entity -> entity.scheduleRetry(truncate(error), nextAttemptAt));
    }

    @Override
    @Transactional
    public void markFailed(Long id, String error) {
        outboxRepository.findById(id).ifPresent(entity -> entity.markFailed(truncate(error)));
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
