package com.a105.zani.recording.application.recoverstalledfinalizations;

/** 애플리케이션 재기동으로 중단된 녹화 병합 작업을 다시 발견되게 만든다. */
public interface RecoverStalledFinalizationsUseCase {

    /** @return 다시 대기시킨 작업 수 */
    int recover();
}
