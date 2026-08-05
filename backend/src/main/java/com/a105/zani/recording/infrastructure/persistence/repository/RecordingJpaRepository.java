package com.a105.zani.recording.infrastructure.persistence.repository;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.a105.zani.recording.infrastructure.persistence.entity.RecordingJpaEntity;

public interface RecordingJpaRepository extends JpaRepository<RecordingJpaEntity, Long> {

    Optional<RecordingJpaEntity> findByLivekitEgressId(String livekitEgressId);

    /** 최종 MP4 합성은 source와 무관하게 모든 Track Egress의 종결을 기다린다(S15P11A105-269). */
    long countBySessionIdAndStatusIn(Long sessionId, Collection<String> statuses);

    /**
     * 발화를 담는 트랙의 녹화 중 해당 상태인 것의 수(S15P11A105-247).
     *
     * <p>사후 전사가 "아직 진행 중인가 / 최종 실패했는가" 를 판정하는 데 쓴다. 화면 공유·카메라 Egress 는 세지 않는다 — 그것이 실패하거나 늦어져도 발화는 마이크 트랙에 그대로 있으므로, 함께
     * 세면 멀쩡한 세션의 전사가 막히거나 불필요하게 기다린다.
     *
     * <p>{@code track_source} 가 {@code NULL} 인 행은 함께 센다. V12 이전에 시작된 Egress 는 종류가 없고, 마이크가 아니라고 단정하면 잃은 발화를 보지 못한다.
     */
    @Query("""
            select count(recording) from RecordingJpaEntity recording
             where recording.sessionId = :sessionId
               and recording.status in :statuses
               and (recording.trackSource is null or recording.trackSource in :sources)
            """)
    long countSpeechRecordings(
            @Param("sessionId") Long sessionId,
            @Param("statuses") Collection<String> statuses,
            @Param("sources") Collection<String> sources);
}
