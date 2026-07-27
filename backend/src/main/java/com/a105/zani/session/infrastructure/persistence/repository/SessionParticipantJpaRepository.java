package com.a105.zani.session.infrastructure.persistence.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.session.infrastructure.persistence.entity.SessionParticipantJpaEntity;

public interface SessionParticipantJpaRepository extends JpaRepository<SessionParticipantJpaEntity, Long> {

    Optional<SessionParticipantJpaEntity> findBySessionIdAndMemberId(Long sessionId, Long memberId);

    List<SessionParticipantJpaEntity> findByMemberId(Long memberId);

    List<SessionParticipantJpaEntity> findBySessionIdOrderByIdAsc(Long sessionId);
}
