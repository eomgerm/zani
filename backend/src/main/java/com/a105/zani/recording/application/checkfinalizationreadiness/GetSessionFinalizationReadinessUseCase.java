package com.a105.zani.recording.application.checkfinalizationreadiness;

/** 세션의 전체 녹화 입력이 최종 MP4 합성을 시작할 만큼 안정됐는지 확인한다. */
public interface GetSessionFinalizationReadinessUseCase {

    FinalizationReadiness check(Long sessionId);
}
