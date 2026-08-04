package com.a105.zani.recording.infrastructure.scheduler;

import org.junit.jupiter.api.Test;

import com.a105.zani.recording.application.recoverstalledfinalizations.RecoverStalledFinalizationsUseCase;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StalledRecordingFinalizationRecoveryTest {

    @Test
    void 애플리케이션이_준비되면_고아_병합_작업을_한_번_회수한다() {
        RecoverStalledFinalizationsUseCase useCase = mock(RecoverStalledFinalizationsUseCase.class);
        StalledRecordingFinalizationRecovery recovery = new StalledRecordingFinalizationRecovery(useCase);

        recovery.recoverOnStartup();

        verify(useCase).recover();
    }

    @Test
    void 복구_저장소가_실패해도_애플리케이션_기동을_막지_않는다() {
        RecoverStalledFinalizationsUseCase useCase = mock(RecoverStalledFinalizationsUseCase.class);
        doThrow(new IllegalStateException("database unavailable")).when(useCase).recover();
        StalledRecordingFinalizationRecovery recovery = new StalledRecordingFinalizationRecovery(useCase);

        assertDoesNotThrow(recovery::recoverOnStartup);
    }
}
