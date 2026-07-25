package com.a105.zani.recording.domain.repository;

import com.a105.zani.recording.domain.model.Recording;

public interface RecordingRepository {

    Recording save(Recording recording);
}
