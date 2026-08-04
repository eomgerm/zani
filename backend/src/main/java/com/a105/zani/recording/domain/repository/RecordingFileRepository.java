package com.a105.zani.recording.domain.repository;

import com.a105.zani.recording.domain.model.RecordingFile;

/**
 * 파일 행 <b>쓰기</b> 전용. 목록 조회는 {@code SessionTrackFileQueryPort} 로 간다.
 *
 * <p>여기에 조회를 두면 반환 타입이 {@link RecordingFile} 이 되고, 그것은 생성 가드를 지나지 않은 객체를 만들어야 한다는 뜻이다. 읽기를 분리하면 그런 객체가 존재하지 않는다.
 */
public interface RecordingFileRepository {

    RecordingFile save(RecordingFile recordingFile);

    /** 같은 파일(storage_key)이 이미 기록됐는지. webhook 재처리가 UNIQUE 제약을 건드리지 않게 하는 멱등 가드. */
    boolean existsByStorageKey(String storageKey);
}
