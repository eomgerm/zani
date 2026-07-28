package com.a105.zani.attention.infrastructure.persistence.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.attention.infrastructure.persistence.entity.CheckPromptJpaEntity;

public interface CheckPromptJpaRepository extends JpaRepository<CheckPromptJpaEntity, Long> {

    Optional<CheckPromptJpaEntity> findBySessionIdAndSessionParticipantIdAndTriggerTypeAndShownOffsetMs(
            Long sessionId, Long sessionParticipantId, String triggerType, Long shownOffsetMs);
}
