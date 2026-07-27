package com.a105.zani.recording.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.a105.zani.recording.infrastructure.persistence.entity.RecordingWebhookEventJpaEntity;

public interface RecordingWebhookEventJpaRepository extends JpaRepository<RecordingWebhookEventJpaEntity, Long> {

    /** event_id UNIQUE 충돌을 호출자 트랜잭션 오염 없이 흡수한다(INSERT IGNORE). 시각은 바인딩으로 넘긴다(타임존 정합). */
    @Modifying
    @Query(
            value = "INSERT IGNORE INTO recording_webhook_events"
                    + " (id, event_id, event_type, payload, status, created_at, updated_at)"
                    + " VALUES (:id, :eventId, :eventType, :payload, 'RECEIVED', :now, :now)",
            nativeQuery = true)
    int insertIgnore(
            @Param("id") Long id,
            @Param("eventId") String eventId,
            @Param("eventType") String eventType,
            @Param("payload") String payload,
            @Param("now") Instant now);

    Optional<RecordingWebhookEventJpaEntity> findByEventId(String eventId);
}
