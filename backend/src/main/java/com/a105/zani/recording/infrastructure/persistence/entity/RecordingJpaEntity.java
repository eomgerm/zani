package com.a105.zani.recording.infrastructure.persistence.entity;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.a105.zani.common.infrastructure.persistence.BaseJpaEntity;
import com.a105.zani.session.infrastructure.persistence.entity.SessionJpaEntity;

@Entity
@Table(name = "recordings")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RecordingJpaEntity extends BaseJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", referencedColumnName = "id", insertable = false, updatable = false)
    private SessionJpaEntity session;

    @Column(name = "livekit_egress_id", nullable = false, length = 255)
    private String livekitEgressId;

    @Column(name = "recording_type", nullable = false, length = 30)
    private String recordingType;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "started_at", columnDefinition = "DATETIME(6)")
    private Instant startedAt;

    @Column(name = "ended_at", columnDefinition = "DATETIME(6)")
    private Instant endedAt;
}
