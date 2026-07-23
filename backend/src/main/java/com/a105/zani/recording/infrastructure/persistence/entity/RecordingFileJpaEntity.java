package com.a105.zani.recording.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.a105.zani.common.infrastructure.persistence.BaseCreatedJpaEntity;
import com.a105.zani.session.infrastructure.persistence.entity.SessionParticipantJpaEntity;

@Entity
@Table(name = "recording_files")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RecordingFileJpaEntity extends BaseCreatedJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "recording_id", nullable = false)
    private Long recordingId;

    @Column(name = "session_participant_id")
    private Long sessionParticipantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
        @JoinColumn(name = "session_id", referencedColumnName = "session_id", insertable = false, updatable = false),
        @JoinColumn(name = "recording_id", referencedColumnName = "id", insertable = false, updatable = false)
    })
    private RecordingJpaEntity recording;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
        @JoinColumn(name = "session_id", referencedColumnName = "session_id", insertable = false, updatable = false),
        @JoinColumn(name = "session_participant_id", referencedColumnName = "id", insertable = false, updatable = false)
    })
    private SessionParticipantJpaEntity sessionParticipant;

    @Column(name = "file_type", nullable = false, length = 30)
    private String fileType;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "livekit_track_sid", length = 255)
    private String livekitTrackSid;

    @Column(name = "started_offset_ms")
    private Long startedOffsetMs;

    @Column(name = "ended_offset_ms")
    private Long endedOffsetMs;
}
