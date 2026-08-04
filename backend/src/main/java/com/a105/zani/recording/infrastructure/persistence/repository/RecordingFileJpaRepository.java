package com.a105.zani.recording.infrastructure.persistence.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.recording.infrastructure.persistence.entity.RecordingFileJpaEntity;

public interface RecordingFileJpaRepository extends JpaRepository<RecordingFileJpaEntity, Long> {

    boolean existsByStorageKey(String storageKey);

    /**
     * 세션의 파일 전체를 <b>id 오름차순</b>으로.
     *
     * <p>{@code started_offset_ms} 로 정렬하지 않는다. 그 값은 NULL 일 수 있어(webhook 에 시각이 없던 파일) 정렬 결과가 DB 설정에 따라 갈린다. id 는 TSID 라
     * 생성 순서를 담고 있고 NULL 이 없다 — 실행마다 같은 순서를 보장하는 쪽을 쓴다.
     */
    List<RecordingFileJpaEntity> findBySessionIdOrderByIdAsc(Long sessionId);
}
