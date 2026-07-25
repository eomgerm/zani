package com.a105.zani.recording.infrastructure.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.a105.zani.recording.domain.model.Recording;
import com.a105.zani.recording.domain.repository.RecordingRepository;
import com.a105.zani.recording.infrastructure.persistence.entity.RecordingJpaEntity;
import com.a105.zani.recording.infrastructure.persistence.mapper.RecordingPersistenceMapper;
import com.a105.zani.recording.infrastructure.persistence.repository.RecordingJpaRepository;

@Component
@RequiredArgsConstructor
public class RecordingPersistenceAdapter implements RecordingRepository {

    private final RecordingJpaRepository recordingJpaRepository;
    private final RecordingPersistenceMapper mapper;

    @Override
    public Recording save(Recording recording) {
        RecordingJpaEntity saved = recordingJpaRepository.saveAndFlush(mapper.toEntity(recording));
        return mapper.toDomain(saved);
    }
}
