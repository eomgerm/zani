package com.a105.zani.recording.application.port;

/** 릴레이가 소비할 PENDING outbox 행. */
public record PendingRecordingOutboxMessage(
        Long id,
        String dedupKey,
        RecordingOutboxType type,
        Long sessionId,
        TrackEgressPayload payload,
        int attemptCount) {}
