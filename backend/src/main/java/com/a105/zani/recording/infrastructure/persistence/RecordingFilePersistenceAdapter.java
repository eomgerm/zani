package com.a105.zani.recording.infrastructure.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.a105.zani.recording.domain.model.RecordingFile;
import com.a105.zani.recording.domain.repository.RecordingFileRepository;
import com.a105.zani.recording.infrastructure.persistence.mapper.RecordingFilePersistenceMapper;
import com.a105.zani.recording.infrastructure.persistence.repository.RecordingFileJpaRepository;

@Component
@RequiredArgsConstructor
public class RecordingFilePersistenceAdapter implements RecordingFileRepository {

    private final RecordingFileJpaRepository recordingFileJpaRepository;
    private final RecordingFilePersistenceMapper mapper;

    @Override
    public RecordingFile save(RecordingFile recordingFile) {
        recordingFileJpaRepository.saveAndFlush(mapper.toEntity(recordingFile));
        return recordingFile;
    }
}
