package com.a105.zani.recording.domain.repository;

import java.util.Optional;

import com.a105.zani.recording.domain.model.Recording;

public interface RecordingRepository {

    Recording save(Recording recording);

    Optional<Recording> findByLivekitEgressId(String livekitEgressId);
}
