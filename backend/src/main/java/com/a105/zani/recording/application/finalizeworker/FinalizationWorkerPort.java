package com.a105.zani.recording.application.finalizeworker;

/** 미디어 worker를 호출한다. 호출자는 이 작업을 DB 트랜잭션 밖에서 실행해야 한다. */
public interface FinalizationWorkerPort {

    FinalizationWorkerResult finalizeRecording(Long sessionId);
}
