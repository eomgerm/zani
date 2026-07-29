package com.a105.zani.session.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.infrastructure.persistence.entity.SessionJpaEntity;

public interface SessionJpaRepository extends JpaRepository<SessionJpaEntity, Long> {

    Optional<SessionJpaEntity> findByInviteCode(String inviteCode);

    /** 정원 검사와 참가 관계 삽입을 직렬화하기 위해 세션 행에 쓰기 잠금을 건다. 반드시 트랜잭션 안에서 호출해야 한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SessionJpaEntity s where s.inviteCode = :inviteCode")
    Optional<SessionJpaEntity> findByInviteCodeForUpdate(@Param("inviteCode") String inviteCode);

    List<SessionJpaEntity> findByStatusAndCreatedAtLessThanEqualOrderByCreatedAtAsc(
            SessionStatus status, Instant createdBefore, Pageable pageable);

    List<SessionJpaEntity> findByStatusAndNoteDueAtLessThanEqualOrderByNoteDueAtAsc(
            SessionStatus status, Instant dueBefore, Pageable pageable);

    List<SessionJpaEntity> findByHostMemberId(Long hostMemberId);

    /** status는 엔티티에서 {@code @Enumerated(EnumType.STRING)} 이므로 문자열이 아니라 enum으로 넘겨야 한다. */
    List<SessionJpaEntity> findByStatusAndStartedAtLessThanEqualOrderByStartedAtAsc(
            SessionStatus status, Instant startedBefore, Pageable pageable);
}
