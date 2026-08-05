package com.a105.zani.recording.infrastructure.persistence.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.a105.zani.recording.infrastructure.persistence.entity.RecordingFileJpaEntity;

public interface RecordingFileJpaRepository extends JpaRepository<RecordingFileJpaEntity, Long> {

    boolean existsByStorageKey(String storageKey);

    /**
     * 세션의 파일 전체를 <b>id 오름차순</b>으로. 부모 {@code recordings} 를 함께 읽는다.
     *
     * <p>{@code started_offset_ms} 로 정렬하지 않는다. 그 값은 NULL 일 수 있어(webhook 에 시각이 없던 파일) 정렬 결과가 DB 설정에 따라 갈린다. id 는 TSID 라
     * 생성 순서를 담고 있고 NULL 이 없다 — 실행마다 같은 순서를 보장하는 쪽을 쓴다.
     *
     * <p><b>부모를 join fetch 하는 이유는 Track SID 다.</b> 한 Egress 가 파일을 여러 개 남기면 {@code UK(recording_id, livekit_track_sid)}
     * 때문에 첫 행만 SID 를 갖고 나머지는 NULL 로 저장된다. 그 NULL 을 그대로 내보내면 후속 파일의 발화가 어느 발행 구간인지 알 수 없는데, 값은 부모
     * {@code recordings.livekit_track_sid} 에 그대로 남아 있다. lazy 로 두면 파일마다 추가 조회가 나가므로 한 번에 읽는다.
     */
    @Query("""
            select file from RecordingFileJpaEntity file
             join fetch file.recording
             where file.sessionId = :sessionId
             order by file.id asc
            """)
    List<RecordingFileJpaEntity> findBySessionIdOrderByIdAsc(@Param("sessionId") Long sessionId);
}
