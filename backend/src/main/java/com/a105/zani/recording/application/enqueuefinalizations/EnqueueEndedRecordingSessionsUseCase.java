package com.a105.zani.recording.application.enqueuefinalizations;

import java.time.Instant;

/** 녹화가 존재하는 종료 세션을 최종 병합 대기열에 등록한다. */
public interface EnqueueEndedRecordingSessionsUseCase {

    int enqueue(int limit, Instant now);
}
