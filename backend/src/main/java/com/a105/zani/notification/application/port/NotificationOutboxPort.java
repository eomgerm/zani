package com.a105.zani.notification.application.port;

import java.time.Instant;
import java.util.List;

/**
 * 알림 발송 outbox 저장소. enqueue 는 dedup_key UNIQUE 로 INSERT IGNORE 해 같은 (세션·수신자·유형) 알림이 두 번 만들어지지 않게 한다(멱등). consumer 는
 * claim 으로 행을 선점해 다중 인스턴스·재시작에서도 같은 알림을 두 번 보내지 않는다. 실패는 시도 횟수·사유로 기록하고 백오프 재시도한다.
 */
public interface NotificationOutboxPort {

    /**
     * outbox 행 하나를 등록한다. 같은 dedup_key 가 이미 있으면 아무것도 하지 않고 {@code false}(중복 방지).
     *
     * @return 이번 호출로 행이 만들어졌으면 {@code true}
     */
    boolean enqueue(NewNotification notification, Instant now);

    /** nextAttemptAt 이 지난 PENDING 행을 오래된 순으로 가져온다(백오프 반영). */
    List<PendingNotification> fetchDue(int limit, Instant now);

    /** PENDING 행을 IN_PROGRESS 로 원자 선점한다. 이미 다른 consumer 가 가져갔으면 {@code false}. 시도 횟수는 이 시점에 오른다. */
    boolean claim(Long id, Instant now);

    /** 크래시 등으로 lease 가 만료된 IN_PROGRESS 행을 PENDING 으로 되돌린다. */
    void requeueExpiredClaims(Instant cutoff, Instant now);

    void markSent(Long id, Instant now);

    /** 백오프 시각과 실패 사유를 남기고 PENDING 으로 되돌려 재시도하게 한다. */
    void markRetry(Long id, String error, Instant nextAttemptAt);

    void markFailed(Long id, String error);
}
