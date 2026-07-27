package com.a105.zani.recording.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.a105.zani.common.infrastructure.persistence.BaseJpaEntity;

@Entity
@Table(name = "recording_webhook_events")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RecordingWebhookEventJpaEntity extends BaseJpaEntity {

    public static final String STATUS_RECEIVED = "RECEIVED";
    public static final String STATUS_PROCESSED = "PROCESSED";

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 100, unique = true)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    public void markProcessed() {
        this.status = STATUS_PROCESSED;
    }
}
