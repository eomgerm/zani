package com.a105.zani.recording.application.port;

import com.a105.zani.recording.application.webhook.RecordingWebhookEvent;

/** LiveKit webhook 서명을 검증하고 내부 이벤트로 변환하는 포트. 서명 불일치는 401로 매핑되는 예외를 던진다. */
public interface RecordingWebhookVerifierPort {

    RecordingWebhookEvent verify(String body, String authorizationHeader);
}
