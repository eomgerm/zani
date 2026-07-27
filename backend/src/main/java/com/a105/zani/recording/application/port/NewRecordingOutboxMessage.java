package com.a105.zani.recording.application.port;

/** outbox에 삽입할 신규 메시지. dedupKey UNIQUE가 같은 작업의 중복 등록을 막는다. payload는 타입에 따라 null일 수 있다. */
public record NewRecordingOutboxMessage(
        String dedupKey, RecordingOutboxType type, Long sessionId, TrackEgressPayload payload) {}
