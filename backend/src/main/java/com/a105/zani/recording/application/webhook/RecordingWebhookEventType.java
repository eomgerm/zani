package com.a105.zani.recording.application.webhook;

/** 처리하는 LiveKit webhook 이벤트 종류. 그 외 이벤트는 IGNORED로 2xx만 반환한다. */
public enum RecordingWebhookEventType {
    /** 참가자가 LiveKit에 연결됐다. 출석(최초 입장)을 확정하는 유일한 근거다(가이드 §5). */
    PARTICIPANT_JOINED,
    /** 참가자가 LiveKit에서 이탈했다. */
    PARTICIPANT_LEFT,
    TRACK_PUBLISHED,
    EGRESS_STARTED,
    EGRESS_UPDATED,
    EGRESS_ENDED,
    IGNORED
}
