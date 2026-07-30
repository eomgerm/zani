package com.a105.zani.recording.application.webhook;

/**
 * 이 수신 경로가 처리하는 LiveKit webhook 이벤트 종류. 그 외 이벤트는 IGNORED로 2xx만 반환한다.
 *
 * <p>LiveKit 은 설정된 webhook URL 하나로 모든 이벤트를 보내므로, 이 엔드포인트는 녹화 이벤트만 받는 게 아니다. PARTICIPANT_* 는 출석 기록용이라 세션 도메인이 처리한다.
 */
public enum RecordingWebhookEventType {
    TRACK_PUBLISHED,
    EGRESS_STARTED,
    EGRESS_UPDATED,
    EGRESS_ENDED,
    PARTICIPANT_JOINED,
    PARTICIPANT_LEFT,
    IGNORED
}
