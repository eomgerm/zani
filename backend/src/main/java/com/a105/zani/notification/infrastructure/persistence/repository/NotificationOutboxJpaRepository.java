package com.a105.zani.notification.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.a105.zani.notification.infrastructure.persistence.entity.NotificationOutboxJpaEntity;

/** notification_outbox 조회·상태 전이. 상태 문자열은 {@link NotificationOutboxJpaEntity} 의 STATUS_* 상수만 쓰고 쿼리에 리터럴을 두지 않는다. */
public interface NotificationOutboxJpaRepository extends JpaRepository<NotificationOutboxJpaEntity, Long> {

    /**
     * dedup_key UNIQUE 충돌을 호출자 트랜잭션 오염 없이 흡수하려고 INSERT IGNORE 를 쓴다. 시각은 DB 함수 대신 바인딩으로 넘겨 서버 타임존·JDBC 변환 어긋남을 막는다.
     *
     * @return 삽입된 행 수(중복이면 0)
     */
    @Modifying
    @Query(
            value = "INSERT IGNORE INTO notification_outbox"
                    + " (id, session_id, member_id, email, display_name, type, dedup_key, status,"
                    + " attempt_count, next_attempt_at, last_error, sent_at, created_at, updated_at)"
                    + " VALUES (:id, :sessionId, :memberId, :email, :displayName, :type, :dedupKey, :status,"
                    + " 0, :now, NULL, NULL, :now, :now)",
            nativeQuery = true)
    int insertIgnoreWithStatus(
            @Param("id") Long id,
            @Param("sessionId") Long sessionId,
            @Param("memberId") Long memberId,
            @Param("email") String email,
            @Param("displayName") String displayName,
            @Param("type") String type,
            @Param("dedupKey") String dedupKey,
            @Param("status") String status,
            @Param("now") Instant now);

    default int insertIgnore(
            Long id,
            Long sessionId,
            Long memberId,
            String email,
            String displayName,
            String type,
            String dedupKey,
            Instant now) {
        return insertIgnoreWithStatus(
                id,
                sessionId,
                memberId,
                email,
                displayName,
                type,
                dedupKey,
                NotificationOutboxJpaEntity.STATUS_PENDING,
                now);
    }

    List<NotificationOutboxJpaEntity> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            String status, Instant now, Pageable pageable);

    /**
     * 지정 상태의 행 하나를 다른 상태로 원자 전환하며 시도 횟수를 올린다.
     *
     * @return 전환된 행 수(이미 다른 consumer 가 가져갔으면 0)
     */
    @Modifying(clearAutomatically = true)
    @Query("update NotificationOutboxJpaEntity n set n.status = :toStatus, n.attemptCount = n.attemptCount + 1,"
            + " n.updatedAt = :now where n.id = :id and n.status = :fromStatus")
    int transitionAndCountAttempt(
            @Param("id") Long id,
            @Param("now") Instant now,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus);

    default int claim(Long id, Instant now) {
        return transitionAndCountAttempt(
                id, now, NotificationOutboxJpaEntity.STATUS_PENDING, NotificationOutboxJpaEntity.STATUS_IN_PROGRESS);
    }

    /** 지정 시각보다 오래 한 상태에 머문 행을 되돌린다. 시도 횟수는 올리지 않는다(선점 때 이미 셌다). */
    @Modifying(clearAutomatically = true)
    @Query("update NotificationOutboxJpaEntity n set n.status = :toStatus, n.updatedAt = :now"
            + " where n.status = :fromStatus and n.updatedAt < :cutoff")
    int transitionStale(
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus);

    default int requeueExpiredClaims(Instant cutoff, Instant now) {
        return transitionStale(
                cutoff,
                now,
                NotificationOutboxJpaEntity.STATUS_IN_PROGRESS,
                NotificationOutboxJpaEntity.STATUS_PENDING);
    }
}
