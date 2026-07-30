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

    /**
     * 세션 행을 잠근 채로 읽는다. 같은 수업으로 들어오는 입장 요청을 직렬화해 정원 검사와 참가자 추가 사이에 다른 요청이 끼어들지 못하게 한다.
     *
     * <p>잠금이 없으면 29명일 때 동시에 들어온 두 요청이 둘 다 "아직 자리가 있다"고 읽어 31명이 된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SessionJpaEntity s WHERE s.inviteCode = :inviteCode")
    Optional<SessionJpaEntity> findByInviteCodeForUpdate(@Param("inviteCode") String inviteCode);

    List<SessionJpaEntity> findByHostMemberId(Long hostMemberId);

    /** status는 엔티티에서 {@code @Enumerated(EnumType.STRING)} 이므로 문자열이 아니라 enum으로 넘겨야 한다. */
    List<SessionJpaEntity> findByStatusAndStartedAtLessThanEqualOrderByStartedAtAsc(
            SessionStatus status, Instant startedBefore, Pageable pageable);

    List<SessionJpaEntity> findByStatusAndCreatedAtLessThanEqualOrderByCreatedAtAsc(
            SessionStatus status, Instant createdBefore, Pageable pageable);
}
