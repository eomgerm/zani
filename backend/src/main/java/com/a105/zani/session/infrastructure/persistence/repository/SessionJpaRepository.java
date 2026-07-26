package com.a105.zani.session.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.session.infrastructure.persistence.entity.SessionJpaEntity;

public interface SessionJpaRepository extends JpaRepository<SessionJpaEntity, Long> {

    Optional<SessionJpaEntity> findByInviteCode(String inviteCode);

    List<SessionJpaEntity> findByHostMemberId(Long hostMemberId);

    List<SessionJpaEntity> findByStatusAndStartedAtLessThanEqualOrderByStartedAtAsc(
            String status, Instant startedBefore, Pageable pageable);
}
