package com.a105.zani.postclass.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.a105.zani.postclass.domain.model.TranscriptionChunkStatus;
import com.a105.zani.postclass.infrastructure.persistence.entity.PostClassTranscriptionChunkJpaEntity;

/** 전사 청크 체크포인트 쿼리(S15P11A105-247). 단계 문자열은 {@link TranscriptionChunkStatus} 만을 단일 소스로 쓰고 쿼리에 리터럴을 두지 않는다. */
public interface PostClassTranscriptionChunkJpaRepository
        extends JpaRepository<PostClassTranscriptionChunkJpaEntity, Long> {

    /**
     * 없을 때만 삽입한다. {@code (recording_file_id, chunk_index)} UNIQUE 충돌을 호출자 트랜잭션 오염 없이 흡수하기 위해 INSERT IGNORE 를 쓴다.
     *
     * <p>JPA save 로 하면 flush 예외가 트랜잭션을 rollback-only 로 만들어, "이미 있으면 넘어간다" 는 계약 때문에 같은 트랜잭션의 앞선 등록까지 되돌아간다. 재분할이 행을 늘리지
     * 않아야 하고 이미 성공한 청크의 결과를 덮어써서도 안 되므로 갱신이 아니라 삽입 무시다.
     *
     * @return 삽입된 행 수(이미 있으면 0)
     */
    @Modifying
    @Query(
            value = "INSERT IGNORE INTO postclass_transcription_chunks"
                    + " (id, session_id, recording_file_id, chunk_index, start_offset_ms, end_offset_ms,"
                    + "  status, attempt_count, created_at, updated_at)"
                    + " VALUES (:id, :sessionId, :recordingFileId, :chunkIndex, :startOffsetMs, :endOffsetMs,"
                    + "  :status, 0, :now, :now)",
            nativeQuery = true)
    int insertIgnore(
            @Param("id") Long id,
            @Param("sessionId") Long sessionId,
            @Param("recordingFileId") Long recordingFileId,
            @Param("chunkIndex") int chunkIndex,
            @Param("startOffsetMs") long startOffsetMs,
            @Param("endOffsetMs") long endOffsetMs,
            @Param("status") String status,
            @Param("now") Instant now);

    /**
     * 지금 처리할 수 있는 청크를 순번대로.
     *
     * <p>{@code PENDING} 은 재시도 대기가 없거나 기한이 지난 것만, {@code PROCESSING} 은 lease 가 만료된 것만 고른다. 후자가 서버가 죽어 남은 행을 회수하는 경로다.
     * 종결 단계는 아예 고르지 않아 성공 청크가 다시 호출되지 않는다.
     */
    @Query("""
            select chunk from PostClassTranscriptionChunkJpaEntity chunk
             where chunk.recordingFileId = :recordingFileId
               and ((chunk.status = :pending
                     and (chunk.nextAttemptAt is null or chunk.nextAttemptAt <= :now))
                 or (chunk.status = :processing
                     and chunk.leaseUntil is not null and chunk.leaseUntil <= :now))
             order by chunk.chunkIndex asc
            """)
    List<PostClassTranscriptionChunkJpaEntity> findClaimable(
            @Param("recordingFileId") Long recordingFileId,
            @Param("pending") String pending,
            @Param("processing") String processing,
            @Param("now") Instant now,
            Pageable pageable);

    default List<PostClassTranscriptionChunkJpaEntity> findClaimable(Long recordingFileId, Instant now, int limit) {
        return findClaimable(
                recordingFileId,
                TranscriptionChunkStatus.PENDING.name(),
                TranscriptionChunkStatus.PROCESSING.name(),
                now,
                Pageable.ofSize(limit));
    }

    /**
     * 선점과 시도 횟수 증가를 한 문장으로 처리한다.
     *
     * <p>조회 후 갱신으로 나누면 두 실행이 같은 청크를 함께 선점할 수 있다. 조건절이 "아직 선점 가능한 상태" 를 확인하므로, 0 행이면 다른 쪽이 먼저 가져간 것이다 — <b>호출자는 반환값을 반드시
     * 확인해야 한다.</b>
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PostClassTranscriptionChunkJpaEntity chunk
               set chunk.status = :processing, chunk.attemptCount = chunk.attemptCount + 1,
                   chunk.leaseUntil = :leaseUntil, chunk.nextAttemptAt = null, chunk.updatedAt = :now
             where chunk.id = :id
               and ((chunk.status = :pending
                     and (chunk.nextAttemptAt is null or chunk.nextAttemptAt <= :now))
                 or (chunk.status = :processing
                     and chunk.leaseUntil is not null and chunk.leaseUntil <= :now))
            """)
    int claim(
            @Param("id") Long id,
            @Param("pending") String pending,
            @Param("processing") String processing,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("now") Instant now);

    default int claim(Long id, Instant leaseUntil, Instant now) {
        return claim(
                id,
                TranscriptionChunkStatus.PENDING.name(),
                TranscriptionChunkStatus.PROCESSING.name(),
                leaseUntil,
                now);
    }

    /**
     * 결과를 기록한다. lease 와 재시도 대기를 함께 푼다.
     *
     * <p><b>실행권을 조건에 넣는다.</b> {@code status = PROCESSING} 이고 {@code attempt_count} 가 선점 때 받은 토큰과 같아야 한다. lease 가 만료돼 다른
     * 실행이 회수했다면 {@code attempt_count} 가 더 올라가 있으므로 늦게 끝난 이전 실행은 0 행을 갱신한다 — 그 결과는 버려야 한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PostClassTranscriptionChunkJpaEntity chunk
               set chunk.status = :status, chunk.resultDocument = :resultDocument,
                   chunk.leaseUntil = null, chunk.nextAttemptAt = null, chunk.updatedAt = :now
             where chunk.id = :id
               and chunk.status = :processing
               and chunk.attemptCount = :fencingToken
            """)
    int markResult(
            @Param("id") Long id,
            @Param("processing") String processing,
            @Param("fencingToken") int fencingToken,
            @Param("status") String status,
            @Param("resultDocument") String resultDocument,
            @Param("now") Instant now);

    default int markResult(
            Long id, int fencingToken, TranscriptionChunkStatus status, String resultDocument, Instant now) {
        return markResult(
                id, TranscriptionChunkStatus.PROCESSING.name(), fencingToken, status.name(), resultDocument, now);
    }

    /**
     * 재시도 대기로 되돌린다. <b>시도 횟수는 건드리지 않는다</b> — {@link #claim} 에서 이미 올렸다.
     *
     * <p>두 곳에서 올리면 한 번의 시도가 두 번으로 세어져 상한에 절반의 속도로 닿는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PostClassTranscriptionChunkJpaEntity chunk
               set chunk.status = :pending, chunk.leaseUntil = null,
                   chunk.nextAttemptAt = :nextAttemptAt, chunk.lastError = :error, chunk.updatedAt = :now
             where chunk.id = :id
               and chunk.status = :processing
               and chunk.attemptCount = :fencingToken
            """)
    int markRetry(
            @Param("id") Long id,
            @Param("pending") String pending,
            @Param("processing") String processing,
            @Param("fencingToken") int fencingToken,
            @Param("error") String error,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("now") Instant now);

    default int markRetry(Long id, int fencingToken, String error, Instant nextAttemptAt, Instant now) {
        return markRetry(
                id,
                TranscriptionChunkStatus.PENDING.name(),
                TranscriptionChunkStatus.PROCESSING.name(),
                fencingToken,
                error,
                nextAttemptAt,
                now);
    }

    /** 최종 실패를 기록한다. 재시도 대기를 풀어 다시 선점되지 않게 한다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PostClassTranscriptionChunkJpaEntity chunk
               set chunk.status = :failed, chunk.leaseUntil = null, chunk.nextAttemptAt = null,
                   chunk.lastError = :error, chunk.updatedAt = :now
             where chunk.id = :id
               and chunk.status = :processing
               and chunk.attemptCount = :fencingToken
            """)
    int markFailed(
            @Param("id") Long id,
            @Param("failed") String failed,
            @Param("processing") String processing,
            @Param("fencingToken") int fencingToken,
            @Param("error") String error,
            @Param("now") Instant now);

    default int markFailed(Long id, int fencingToken, String error, Instant now) {
        return markFailed(
                id,
                TranscriptionChunkStatus.FAILED.name(),
                TranscriptionChunkStatus.PROCESSING.name(),
                fencingToken,
                error,
                now);
    }

    /** 한 원본 파일의 체크포인트 전체를 순번대로. 등록 직후 경계 대조에 쓴다. */
    List<PostClassTranscriptionChunkJpaEntity> findByRecordingFileIdOrderByChunkIndexAsc(Long recordingFileId);

    List<PostClassTranscriptionChunkJpaEntity> findBySessionIdOrderByRecordingFileIdAscChunkIndexAsc(Long sessionId);

    void deleteBySessionId(Long sessionId);
}
