package com.a105.zani.recording.infrastructure.persistence.mapper;

import org.springframework.stereotype.Component;

import com.a105.zani.recording.domain.model.RecordingFile;
import com.a105.zani.recording.infrastructure.persistence.entity.RecordingFileJpaEntity;

@Component
public class RecordingFilePersistenceMapper {

    public RecordingFileJpaEntity toEntity(RecordingFile file) {
        return RecordingFileJpaEntity.builder()
                .id(file.id())
                .sessionId(file.sessionId())
                .recordingId(file.recordingId())
                .sessionParticipantId(file.sessionParticipantId())
                .fileType(file.fileType())
                .storageKey(file.storageKey())
                .livekitTrackSid(file.livekitTrackSid())
                .startedOffsetMs(file.startedOffsetMs())
                .endedOffsetMs(file.endedOffsetMs())
                .build();
    }
}
