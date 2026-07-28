package com.a105.zani.recording.infrastructure.persistence.mapper;

import org.springframework.stereotype.Component;

import com.a105.zani.recording.domain.model.Recording;
import com.a105.zani.recording.domain.model.RecordingStatus;
import com.a105.zani.recording.infrastructure.persistence.entity.RecordingJpaEntity;

@Component
public class RecordingPersistenceMapper {

    public RecordingJpaEntity toEntity(Recording recording) {
        return RecordingJpaEntity.builder()
                .id(recording.id())
                .sessionId(recording.sessionId())
                .livekitEgressId(recording.livekitEgressId())
                .recordingType(recording.recordingType())
                .attemptNumber(recording.attemptNumber())
                .status(recording.status().name())
                .startedAt(recording.startedAt())
                .endedAt(recording.endedAt())
                .build();
    }

    public Recording toDomain(RecordingJpaEntity entity) {
        return Recording.reconstitute(
                entity.getId(),
                entity.getSessionId(),
                entity.getLivekitEgressId(),
                entity.getRecordingType(),
                entity.getAttemptNumber(),
                RecordingStatus.valueOf(entity.getStatus()),
                entity.getStartedAt(),
                entity.getEndedAt());
    }
}
