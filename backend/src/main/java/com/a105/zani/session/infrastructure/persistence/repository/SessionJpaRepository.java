package com.a105.zani.session.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.infrastructure.persistence.entity.SessionJpaEntity;

public interface SessionJpaRepository extends JpaRepository<SessionJpaEntity, Long> {

    Optional<SessionJpaEntity> findByInviteCode(String inviteCode);

    List<SessionJpaEntity> findByHostMemberId(Long hostMemberId);

    /** status는 엔티티에서 {@code @Enumerated(EnumType.STRING)} 이므로 문자열이 아니라 enum으로 넘겨야 한다. */
    List<SessionJpaEntity> findByStatusAndStartedAtLessThanEqualOrderByStartedAtAsc(
            SessionStatus status, Instant startedBefore, Pageable pageable);
}
