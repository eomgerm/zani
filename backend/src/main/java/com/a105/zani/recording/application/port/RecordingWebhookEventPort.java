package com.a105.zani.recording.application.port;

/**
 * webhook 이벤트의 내구성 저장 + 중복 방지 포트(event_id UNIQUE). 처리 실패 시 RECEIVED로 남아 LiveKit 재전송에서 재처리되고, PROCESSED가 된 이벤트는 다시 처리하지
 * 않는다.
 */
public interface RecordingWebhookEventPort {

    /** 이벤트를 저장하고 처리 시작 가능 여부를 반환한다. 이미 PROCESSED면 false(중복), RECEIVED로 남아 있으면 true(재처리). */
    boolean begin(String eventId, String eventType, String payload);

    void markProcessed(String eventId);
}
