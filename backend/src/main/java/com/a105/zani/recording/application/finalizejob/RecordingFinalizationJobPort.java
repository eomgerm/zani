package com.a105.zani.recording.application.finalizejob;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 최종 병합 작업 큐. 모든 변경은 짧은 자체 트랜잭션으로 끝나며 worker 실행을 감싸지 않는다. */
public interface RecordingFinalizationJobPort {

    int enqueueEndedSessions(int limit, Instant now);

    List<Long> findDueSessionIds(Instant now, int limit);

    /**
     * 애플리케이션 재기동으로 worker가 사라졌지만 {@code RUNNING}으로 남은 작업을 다시 대기시킨다.
     *
     * <p>기동 시점에만 호출한다. 시도 횟수는 실제 worker 실패가 아니므로 유지하고, fencing token은 올려 이전 프로세스의 늦은 결과를 차단한다.
     */
    int requeueRunningJobs(Instant now);

    Optional<FinalizationJobLease> tryClaim(Long sessionId, Instant leaseUntil, Instant now);

    Optional<FinalizationJobLease> beginAttempt(FinalizationJobLease lease, Instant now);

    boolean markWaiting(FinalizationJobLease lease, Instant nextAttemptAt, Instant now);

    /** worker exit 9는 실제 시도가 아니므로 방금 증가한 attempt를 되돌리고 다시 대기한다. */
    boolean markContended(FinalizationJobLease lease, Instant nextAttemptAt, Instant now);

    boolean markRetry(FinalizationJobLease lease, String error, Instant nextAttemptAt, Instant now);

    boolean markFailed(FinalizationJobLease lease, String error, Instant now);

    boolean markCompleted(
            FinalizationJobLease lease,
            String manifestPath,
            String outputPath,
            long outputSizeBytes,
            String outputSha256,
            Instant now);
}
