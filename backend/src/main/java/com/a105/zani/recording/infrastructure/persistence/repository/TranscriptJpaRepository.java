package com.a105.zani.recording.infrastructure.persistence.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.recording.infrastructure.persistence.entity.TranscriptJpaEntity;

public interface TranscriptJpaRepository extends JpaRepository<TranscriptJpaEntity, Long> {

    Optional<TranscriptJpaEntity> findBySessionId(Long sessionId);
}
