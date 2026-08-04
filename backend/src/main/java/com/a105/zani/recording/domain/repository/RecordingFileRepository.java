package com.a105.zani.recording.domain.repository;

import java.util.List;

import com.a105.zani.recording.domain.model.RecordingFile;

public interface RecordingFileRepository {

    RecordingFile save(RecordingFile recordingFile);

    /** 같은 파일(storage_key)이 이미 기록됐는지. webhook 재처리가 UNIQUE 제약을 건드리지 않게 하는 멱등 가드. */
    boolean existsByStorageKey(String storageKey);

    /** 세션이 남긴 파일 전체를 id 오름차순으로. 사후 전사가 전사 대상 트랙을 고르는 데 쓴다(S15P11A105-247). */
    List<RecordingFile> findBySessionId(Long sessionId);
}
