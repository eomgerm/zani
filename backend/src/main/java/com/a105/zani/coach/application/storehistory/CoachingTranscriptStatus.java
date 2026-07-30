package com.a105.zani.coach.application.storehistory;

/** Whether instructor transcription was required and what happened for one coaching trigger. */
public enum CoachingTranscriptStatus {
    TRANSCRIBED,
    SKIPPED_NOT_REQUIRED,
    NOT_ATTEMPTED,
    TRANSCRIPTION_FAILED,
    NO_TRANSCRIPT
}
