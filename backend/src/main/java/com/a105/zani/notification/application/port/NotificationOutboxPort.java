package com.a105.zani.notification.application.port;

import java.time.Instant;
import java.util.List;

/**
 * 알림 발송 outbox 저장소. enqueue 는 dedup_key UNIQUE 로 INSERT IGNORE 해 같은 (세션·수신자·유형) 알림이 두 번 만들어지지 않게 한다(멱등). consumer 는
 * claim 으로 행을 선점해 다중 인스턴스·재시작에서도 같은 알림을 두 번 보내지 않는다. 실패는 시도 횟수·사유로 기록하고 백오프 재시도한다.
 */
public interface NotificationOutboxPort {

    /**
     * 한 세션의 알림들을 <b>한 트랜잭션</b>에서 등록한다. 도중 실패하면 전부 롤백돼 그 세션은 다음 폴링에서 다시 발견·재시도된다 — 일부 수신자만 등록되고 나머지가 영영 누락되는 일을 막는다(발견
     * 조건이 세션·유형 단위라 한 건이라도 남으면 재발견에서 빠지기 때문). 각 행은 dedup_key UNIQUE 로 INSERT IGNORE 되어 재실행에도 멱등이다.
     *
     * @return 이번 호출로 새로 만들어진 행 수
     */
    int enqueueAll(List<NewNotification> notifications, Instant now);

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
