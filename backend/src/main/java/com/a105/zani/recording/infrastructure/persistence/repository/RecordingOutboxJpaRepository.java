package com.a105.zani.recording.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.a105.zani.recording.infrastructure.persistence.entity.RecordingOutboxJpaEntity;

public interface RecordingOutboxJpaRepository extends JpaRepository<RecordingOutboxJpaEntity, Long> {

    /**
     * dedup_key UNIQUE 충돌을 호출자 트랜잭션 오염 없이 처리하기 위해 INSERT IGNORE를 쓴다. (JPA save의 flush 예외는 트랜잭션을 rollback-only로 만들어
     * "중복이면 조용히 무시" 계약을 지킬 수 없다.)
     *
     * @return 삽입된 행 수(중복이면 0)
     */
    @Modifying
    @Query(
            value = "INSERT IGNORE INTO recording_outbox"
                    + " (id, dedup_key, outbox_type, session_id, payload, status, attempt_count, next_attempt_at,"
                    + " last_error, created_at, updated_at)"
                    + " VALUES (:id, :dedupKey, :outboxType, :sessionId, :payload, 'PENDING', 0, :now,"
                    + " NULL, :now, :now)",
            nativeQuery = true)
    // 시각은 DB 함수(NOW) 대신 바인딩으로 넘긴다: 서버 타임존과 JDBC 변환이 어긋나면 next_attempt_at 비교가 틀어진다.
    int insertIgnore(
            @Param("id") Long id,
            @Param("dedupKey") String dedupKey,
            @Param("outboxType") String outboxType,
            @Param("sessionId") Long sessionId,
            @Param("payload") String payload,
            @Param("now") Instant now);

    List<RecordingOutboxJpaEntity> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            String status, Instant now, Pageable pageable);

    /**
     * PENDING 행 하나를 IN_PROGRESS로 원자적으로 전환한다(claim). 다중 인스턴스·중복 스케줄 실행에서 같은 행이 두 번 수행되지 않게 하는 잠금 역할이며, 시도 횟수도 이 시점에 올린다.
     *
     * @return 전환된 행 수(이미 다른 릴레이가 가져갔으면 0)
     */
    @Modifying(clearAutomatically = true)
    @Query("update RecordingOutboxJpaEntity o set o.status = 'IN_PROGRESS', o.attemptCount = o.attemptCount + 1,"
            + " o.updatedAt = :now where o.id = :id and o.status = 'PENDING'")
    int claim(@Param("id") Long id, @Param("now") Instant now);

    /** 처리 도중 크래시 등으로 IN_PROGRESS에 방치된 행(lease 만료)을 PENDING으로 되돌린다. */
    @Modifying(clearAutomatically = true)
    @Query("update RecordingOutboxJpaEntity o set o.status = 'PENDING', o.updatedAt = :now"
            + " where o.status = 'IN_PROGRESS' and o.updatedAt < :cutoff")
    int requeueExpiredClaims(@Param("cutoff") Instant cutoff, @Param("now") Instant now);
}
