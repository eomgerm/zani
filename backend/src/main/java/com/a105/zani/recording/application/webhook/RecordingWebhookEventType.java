package com.a105.zani.recording.application.webhook;

/** 녹화 파이프라인이 처리하는 LiveKit webhook 이벤트 종류. 그 외 이벤트는 IGNORED로 2xx만 반환한다. */
public enum RecordingWebhookEventType {
    TRACK_PUBLISHED,
    EGRESS_STARTED,
    EGRESS_UPDATED,
    EGRESS_ENDED,
    IGNORED
}
