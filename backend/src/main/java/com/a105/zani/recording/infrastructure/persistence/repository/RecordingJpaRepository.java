package com.a105.zani.recording.infrastructure.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.recording.infrastructure.persistence.entity.RecordingJpaEntity;

public interface RecordingJpaRepository extends JpaRepository<RecordingJpaEntity, Long> {

    java.util.Optional<RecordingJpaEntity> findByLivekitEgressId(String livekitEgressId);
}
