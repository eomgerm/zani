package com.a105.zani.recording.application.webhook;

/**
 * 이 webhook 수신 경로가 처리하는 LiveKit 이벤트 종류. 그 외 이벤트는 IGNORED로 2xx만 반환한다.
 *
 * <p>{@code PARTICIPANT_JOINED}는 녹화가 아니라 세션 참여 자격을 위한 것이다. 서명 검증·{@code event_id} 멱등·트랜잭션이 이 경로에만 있어 여기서 받고 세션 도메인
 * UseCase 로 넘긴다.
 */
public enum LiveKitWebhookEventType {
    PARTICIPANT_JOINED,
    TRACK_PUBLISHED,
    EGRESS_STARTED,
    EGRESS_UPDATED,
    EGRESS_ENDED,
    IGNORED
}
