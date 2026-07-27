package com.a105.zani.recording.domain.repository;

import com.a105.zani.recording.domain.model.RecordingFile;

public interface RecordingFileRepository {

    RecordingFile save(RecordingFile recordingFile);

    /** 같은 파일(storage_key)이 이미 기록됐는지. webhook 재처리가 UNIQUE 제약을 건드리지 않게 하는 멱등 가드. */
    boolean existsByStorageKey(String storageKey);
}
