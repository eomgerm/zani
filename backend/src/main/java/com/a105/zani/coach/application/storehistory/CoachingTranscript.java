package com.a105.zani.coach.application.storehistory;

import java.time.Instant;

/** Instructor transcript status and exact audio interval retained without duplicating transcript text. */
public record CoachingTranscript(CoachingTranscriptStatus status, Instant startedAt, Instant endedAt) {

    public CoachingTranscript {
        if (status == null) {
            throw new IllegalArgumentException("transcript status is required");
        }
        if (status == CoachingTranscriptStatus.TRANSCRIBED) {
            if (startedAt == null || endedAt == null) {
                throw new IllegalArgumentException("a transcribed interval requires exact timestamps");
            }
        }
        if (startedAt != null && endedAt != null && endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("transcript end must not precede its start");
        }
    }

    public static CoachingTranscript transcribed(long fromEpochMs, long toEpochMs) {
        return new CoachingTranscript(
                CoachingTranscriptStatus.TRANSCRIBED,
                Instant.ofEpochMilli(fromEpochMs),
                Instant.ofEpochMilli(toEpochMs));
    }

    public static CoachingTranscript noTranscript(Long fromEpochMs, Long toEpochMs) {
        return new CoachingTranscript(
                CoachingTranscriptStatus.NO_TRANSCRIPT, instantOf(fromEpochMs), instantOf(toEpochMs));
    }

    public static CoachingTranscript skippedNotRequired() {
        return statusOnly(CoachingTranscriptStatus.SKIPPED_NOT_REQUIRED);
    }

    public static CoachingTranscript notAttempted() {
        return statusOnly(CoachingTranscriptStatus.NOT_ATTEMPTED);
    }

    public static CoachingTranscript failed() {
        return statusOnly(CoachingTranscriptStatus.TRANSCRIPTION_FAILED);
    }

    private static CoachingTranscript statusOnly(CoachingTranscriptStatus status) {
        return new CoachingTranscript(status, null, null);
    }

    private static Instant instantOf(Long epochMs) {
        return epochMs == null ? null : Instant.ofEpochMilli(epochMs);
    }
}
