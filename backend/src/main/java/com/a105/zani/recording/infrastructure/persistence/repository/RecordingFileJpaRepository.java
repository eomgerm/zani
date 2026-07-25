package com.a105.zani.recording.infrastructure.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.recording.infrastructure.persistence.entity.RecordingFileJpaEntity;

public interface RecordingFileJpaRepository extends JpaRepository<RecordingFileJpaEntity, Long> {

    boolean existsByStorageKey(String storageKey);
}
