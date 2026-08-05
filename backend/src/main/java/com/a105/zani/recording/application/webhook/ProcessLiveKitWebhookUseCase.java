package com.a105.zani.recording.application.webhook;

public interface ProcessLiveKitWebhookUseCase {

    /** 서명을 검증하고 이벤트를 내구 저장한 뒤 처리한다. 서명 불일치는 401, 처리 불가 상태는 5xx로 매핑된다. */
    void process(String body, String authorizationHeader);
}
