package com.a105.zani.recording.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.a105.zani.recording.domain.model.RecordingFinalizationStatus;
import com.a105.zani.recording.infrastructure.persistence.entity.RecordingFinalizationJobJpaEntity;

public interface RecordingFinalizationJobJpaRepository extends JpaRepository<RecordingFinalizationJobJpaEntity, Long> {

    @Query(value = """
                    select distinct recording.session_id
                      from recordings recording
                     where not exists (
                           select 1 from recording_finalization_jobs job
                            where job.session_id = recording.session_id)
                     order by recording.session_id asc
                    """, nativeQuery = true)
    List<Long> findUnqueuedRecordedSessionIds();

    @Modifying
    @Query(value = """
                    INSERT IGNORE INTO recording_finalization_jobs
                        (id, session_id, status, attempt_count, lease_token, created_at, updated_at)
                    VALUES (:id, :sessionId, :status, 0, 0, :now, :now)
                    """, nativeQuery = true)
    int insertIgnore(
            @Param("id") Long id,
            @Param("sessionId") Long sessionId,
            @Param("status") String status,
            @Param("now") Instant now);

    @Query("""
            select job.sessionId from RecordingFinalizationJobJpaEntity job
             where (job.status = :pending and (job.nextAttemptAt is null or job.nextAttemptAt <= :now))
                or (job.status = :running and job.leaseUntil is not null and job.leaseUntil <= :now)
             order by job.sessionId asc
            """)
    List<Long> findDueSessionIds(
            @Param("pending") String pending,
            @Param("running") String running,
            @Param("now") Instant now,
            Pageable pageable);

    default List<Long> findDueSessionIds(Instant now, int limit) {
        return findDueSessionIds(
                RecordingFinalizationStatus.PENDING.name(),
                RecordingFinalizationStatus.RUNNING.name(),
                now,
                Pageable.ofSize(limit));
    }

    /**
     * 기동 전에 종료된 인스턴스가 남긴 실행권을 회수한다.
     *
     * <p>{@code leaseToken}을 함께 올려 이전 worker가 뒤늦게 결과를 기록하지 못하게 하고, 실제 worker 실패가 아니므로 {@code attemptCount}는 유지한다. 단일
     * 백엔드 인스턴스의 기동 시점 전용 쿼리다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RecordingFinalizationJobJpaEntity job
               set job.status = :pending, job.leaseToken = job.leaseToken + 1,
                   job.leaseUntil = null, job.nextAttemptAt = :now,
                   job.lastError = :reason, job.updatedAt = :now
             where job.status = :running
            """)
    int requeueRunningJobs(
            @Param("pending") String pending,
            @Param("running") String running,
            @Param("reason") String reason,
            @Param("now") Instant now);

    default int requeueRunningJobs(Instant now) {
        return requeueRunningJobs(
                RecordingFinalizationStatus.PENDING.name(),
                RecordingFinalizationStatus.RUNNING.name(),
                "application_restarted",
                now);
    }

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RecordingFinalizationJobJpaEntity job
               set job.status = :running, job.leaseToken = job.leaseToken + 1,
                   job.leaseUntil = :leaseUntil, job.nextAttemptAt = null, job.updatedAt = :now
             where job.sessionId = :sessionId
               and ((job.status = :pending and (job.nextAttemptAt is null or job.nextAttemptAt <= :now))
                 or (job.status = :running and job.leaseUntil is not null and job.leaseUntil <= :now))
            """)
    int claim(
            @Param("sessionId") Long sessionId,
            @Param("pending") String pending,
            @Param("running") String running,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("now") Instant now);

    default int claim(Long sessionId, Instant leaseUntil, Instant now) {
        return claim(
                sessionId,
                RecordingFinalizationStatus.PENDING.name(),
                RecordingFinalizationStatus.RUNNING.name(),
                leaseUntil,
                now);
    }

    Optional<RecordingFinalizationJobJpaEntity> findBySessionId(Long sessionId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RecordingFinalizationJobJpaEntity job
               set job.attemptCount = job.attemptCount + 1,
                   job.startedAt = coalesce(job.startedAt, :now), job.updatedAt = :now
             where job.sessionId = :sessionId and job.status = :running and job.leaseToken = :leaseToken
            """)
    int beginAttempt(
            @Param("sessionId") Long sessionId,
            @Param("running") String running,
            @Param("leaseToken") int leaseToken,
            @Param("now") Instant now);

    default int beginAttempt(Long sessionId, int leaseToken, Instant now) {
        return beginAttempt(sessionId, RecordingFinalizationStatus.RUNNING.name(), leaseToken, now);
    }

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RecordingFinalizationJobJpaEntity job
               set job.status = :pending, job.leaseUntil = null, job.nextAttemptAt = :nextAttemptAt,
                   job.updatedAt = :now
             where job.sessionId = :sessionId and job.status = :running and job.leaseToken = :leaseToken
            """)
    int markWaiting(
            @Param("sessionId") Long sessionId,
            @Param("pending") String pending,
            @Param("running") String running,
            @Param("leaseToken") int leaseToken,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("now") Instant now);

    default int markWaiting(Long sessionId, int leaseToken, Instant nextAttemptAt, Instant now) {
        return markWaiting(
                sessionId,
                RecordingFinalizationStatus.PENDING.name(),
                RecordingFinalizationStatus.RUNNING.name(),
                leaseToken,
                nextAttemptAt,
                now);
    }

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RecordingFinalizationJobJpaEntity job
               set job.status = :pending, job.attemptCount = case when job.attemptCount > 0
                       then job.attemptCount - 1 else 0 end,
                   job.leaseUntil = null, job.nextAttemptAt = :nextAttemptAt, job.updatedAt = :now
             where job.sessionId = :sessionId and job.status = :running and job.leaseToken = :leaseToken
            """)
    int markContended(
            @Param("sessionId") Long sessionId,
            @Param("pending") String pending,
            @Param("running") String running,
            @Param("leaseToken") int leaseToken,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("now") Instant now);

    default int markContended(Long sessionId, int leaseToken, Instant nextAttemptAt, Instant now) {
        return markContended(
                sessionId,
                RecordingFinalizationStatus.PENDING.name(),
                RecordingFinalizationStatus.RUNNING.name(),
                leaseToken,
                nextAttemptAt,
                now);
    }

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RecordingFinalizationJobJpaEntity job
               set job.status = :pending, job.leaseUntil = null, job.nextAttemptAt = :nextAttemptAt,
                   job.lastError = :error, job.updatedAt = :now
             where job.sessionId = :sessionId and job.status = :running and job.leaseToken = :leaseToken
            """)
    int markRetry(
            @Param("sessionId") Long sessionId,
            @Param("pending") String pending,
            @Param("running") String running,
            @Param("leaseToken") int leaseToken,
            @Param("error") String error,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("now") Instant now);

    default int markRetry(Long sessionId, int leaseToken, String error, Instant nextAttemptAt, Instant now) {
        return markRetry(
                sessionId,
                RecordingFinalizationStatus.PENDING.name(),
                RecordingFinalizationStatus.RUNNING.name(),
                leaseToken,
                error,
                nextAttemptAt,
                now);
    }

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RecordingFinalizationJobJpaEntity job
               set job.status = :failed, job.leaseUntil = null, job.nextAttemptAt = null,
                   job.lastError = :error, job.updatedAt = :now
             where job.sessionId = :sessionId and job.status = :running and job.leaseToken = :leaseToken
            """)
    int markFailed(
            @Param("sessionId") Long sessionId,
            @Param("failed") String failed,
            @Param("running") String running,
            @Param("leaseToken") int leaseToken,
            @Param("error") String error,
            @Param("now") Instant now);

    default int markFailed(Long sessionId, int leaseToken, String error, Instant now) {
        return markFailed(
                sessionId,
                RecordingFinalizationStatus.FAILED.name(),
                RecordingFinalizationStatus.RUNNING.name(),
                leaseToken,
                error,
                now);
    }

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RecordingFinalizationJobJpaEntity job
               set job.status = :completed, job.leaseUntil = null, job.nextAttemptAt = null,
                   job.lastError = null, job.manifestPath = :manifestPath, job.outputPath = :outputPath,
                   job.outputSizeBytes = :outputSizeBytes, job.outputSha256 = :outputSha256,
                   job.completedAt = :now, job.updatedAt = :now
             where job.sessionId = :sessionId and job.status = :running and job.leaseToken = :leaseToken
            """)
    int markCompleted(
            @Param("sessionId") Long sessionId,
            @Param("completed") String completed,
            @Param("running") String running,
            @Param("leaseToken") int leaseToken,
            @Param("manifestPath") String manifestPath,
            @Param("outputPath") String outputPath,
            @Param("outputSizeBytes") long outputSizeBytes,
            @Param("outputSha256") String outputSha256,
            @Param("now") Instant now);

    default int markCompleted(
            Long sessionId,
            int leaseToken,
            String manifestPath,
            String outputPath,
            long outputSizeBytes,
            String outputSha256,
            Instant now) {
        return markCompleted(
                sessionId,
                RecordingFinalizationStatus.COMPLETED.name(),
                RecordingFinalizationStatus.RUNNING.name(),
                leaseToken,
                manifestPath,
                outputPath,
                outputSizeBytes,
                outputSha256,
                now);
    }
}
