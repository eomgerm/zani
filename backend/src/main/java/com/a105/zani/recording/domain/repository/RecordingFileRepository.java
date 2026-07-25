package com.a105.zani.recording.domain.repository;

import com.a105.zani.recording.domain.model.RecordingFile;

public interface RecordingFileRepository {

    RecordingFile save(RecordingFile recordingFile);
}
