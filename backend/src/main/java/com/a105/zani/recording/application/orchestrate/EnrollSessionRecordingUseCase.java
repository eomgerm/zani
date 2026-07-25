package com.a105.zani.recording.application.orchestrate;

public interface EnrollSessionRecordingUseCase {

    /**
     * 방(세션) 생성과 같은 트랜잭션에서 호출되어 녹화 등록 outbox 행을 남긴다. 세션 insert가 롤백되면 outbox 행도 함께 롤백된다(transactional outbox). 이미 등록된
     * 세션이면 조용히 무시한다.
     */
    void enroll(Long sessionId);
}
