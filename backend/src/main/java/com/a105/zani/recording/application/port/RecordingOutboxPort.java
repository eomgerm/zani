package com.a105.zani.recording.application.port;

import java.time.Instant;
import java.util.List;

/**
 * recording_outbox 저장소 포트. enqueue는 호출자의 DB 트랜잭션에 참여해 비즈니스 쓰기와 원자적으로 기록되고, dedupKey 충돌(이미 등록된 작업)이면 호출자 트랜잭션을 오염시키지 않고
 * false를 반환한다. 릴레이는 claim으로 행을 선점한 뒤 처리해, 다중 인스턴스나 재시작 상황에서도 같은 작업이 두 번 수행되지 않는다.
 */
public interface RecordingOutboxPort {

    /** outbox 행을 삽입한다. 같은 dedupKey가 이미 있으면 false(중복 방지). 호출자 트랜잭션은 유지된다. */
    boolean enqueue(NewRecordingOutboxMessage message);

    /** nextAttemptAt이 지난 PENDING 행을 오래된 순으로 가져온다(백오프 반영). */
    List<PendingRecordingOutboxMessage> fetchDue(int limit, Instant now);

    /** 행을 IN_PROGRESS로 원자 선점한다. 이미 다른 릴레이가 가져갔으면 false. 시도 횟수는 이 시점에 오른다. */
    boolean claim(Long id, Instant now);

    /** 크래시 등으로 방치된 IN_PROGRESS 행(lease 만료)을 PENDING으로 되돌린다. */
    void requeueExpiredClaims(Instant cutoff, Instant now);

    void markCompleted(Long id);

    /** 백오프 시각과 실패 사유를 기록하고 PENDING으로 되돌려 재시도하게 한다. */
    void markRetry(Long id, String error, Instant nextAttemptAt);

    void markFailed(Long id, String error);
}
