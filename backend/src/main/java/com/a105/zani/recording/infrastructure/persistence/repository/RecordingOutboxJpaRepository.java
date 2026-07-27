package com.a105.zani.recording.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.a105.zani.recording.infrastructure.persistence.entity.RecordingOutboxJpaEntity;

/**
 * recording_outbox 조회·상태 전이 쿼리. 상태 문자열은 {@link RecordingOutboxJpaEntity}의 STATUS_* 상수만을 단일 소스로 쓰고 쿼리에는 리터럴을 두지 않는다. 상태를
 * 바인딩하는 쿼리 메서드는 default 메서드로 감싸, 호출부는 상태 대신 의도(claim·requeue)만 표현한다.
 */
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
                    + " VALUES (:id, :dedupKey, :outboxType, :sessionId, :payload, :status, 0, :now,"
                    + " NULL, :now, :now)",
            nativeQuery = true)
    // 시각은 DB 함수(NOW) 대신 바인딩으로 넘긴다: 서버 타임존과 JDBC 변환이 어긋나면 next_attempt_at 비교가 틀어진다.
    int insertIgnoreWithStatus(
            @Param("id") Long id,
            @Param("dedupKey") String dedupKey,
            @Param("outboxType") String outboxType,
            @Param("sessionId") Long sessionId,
            @Param("payload") String payload,
            @Param("status") String status,
            @Param("now") Instant now);

    /** 새 작업은 항상 PENDING으로 등록한다. */
    default int insertIgnore(Long id, String dedupKey, String outboxType, Long sessionId, String payload, Instant now) {
        return insertIgnoreWithStatus(
                id, dedupKey, outboxType, sessionId, payload, RecordingOutboxJpaEntity.STATUS_PENDING, now);
    }

    List<RecordingOutboxJpaEntity> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            String status, Instant now, Pageable pageable);

    /**
     * 지정 상태의 행 하나를 다른 상태로 원자적으로 전환하며 시도 횟수를 올린다.
     *
     * @return 전환된 행 수(이미 다른 릴레이가 가져갔으면 0)
     */
    @Modifying(clearAutomatically = true)
    @Query("update RecordingOutboxJpaEntity o set o.status = :toStatus, o.attemptCount = o.attemptCount + 1,"
            + " o.updatedAt = :now where o.id = :id and o.status = :fromStatus")
    int transitionAndCountAttempt(
            @Param("id") Long id,
            @Param("now") Instant now,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus);

    /** PENDING 행 하나를 IN_PROGRESS로 선점한다(claim). 다중 인스턴스·중복 스케줄 실행에서 같은 행이 두 번 수행되지 않게 하는 잠금 역할이다. */
    default int claim(Long id, Instant now) {
        return transitionAndCountAttempt(
                id, now, RecordingOutboxJpaEntity.STATUS_PENDING, RecordingOutboxJpaEntity.STATUS_IN_PROGRESS);
    }

    /**
     * 지정 시각보다 오래 한 상태에 머문 행들을 다른 상태로 되돌린다. 시도 횟수는 올리지 않는다(선점 시점에 이미 셌다).
     *
     * @return 되돌린 행 수
     */
    @Modifying(clearAutomatically = true)
    @Query("update RecordingOutboxJpaEntity o set o.status = :toStatus, o.updatedAt = :now"
            + " where o.status = :fromStatus and o.updatedAt < :cutoff")
    int transitionStale(
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus);

    /** 처리 도중 크래시 등으로 IN_PROGRESS에 방치된 행(lease 만료)을 PENDING으로 되돌린다. */
    default int requeueExpiredClaims(Instant cutoff, Instant now) {
        return transitionStale(
                cutoff, now, RecordingOutboxJpaEntity.STATUS_IN_PROGRESS, RecordingOutboxJpaEntity.STATUS_PENDING);
    }
}
