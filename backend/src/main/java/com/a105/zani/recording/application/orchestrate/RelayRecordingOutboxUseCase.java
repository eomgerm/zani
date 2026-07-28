package com.a105.zani.recording.application.orchestrate;

public interface RelayRecordingOutboxUseCase {

    /** PENDING outbox 행을 소비해 외부 작업(Track Egress 시작 등)을 수행한다. 처리한 행 수를 반환한다. */
    int relayPendingOutbox();
}
