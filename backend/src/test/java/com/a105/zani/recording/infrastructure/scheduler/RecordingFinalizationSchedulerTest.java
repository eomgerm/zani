package com.a105.zani.recording.infrastructure.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.a105.zani.recording.application.enqueuefinalizations.EnqueueEndedRecordingSessionsUseCase;
import com.a105.zani.recording.application.finalizejob.FinalizationJobLease;
import com.a105.zani.recording.application.finalizejob.RecordingFinalizationJobPort;
import com.a105.zani.recording.application.finalizerecording.FinalizeRecordingUseCase;
import com.a105.zani.recording.infrastructure.config.RecordingFinalizationDispatchProperties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecordingFinalizationSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-04T10:00:00Z");

    @Test
    void executor가_거절하면_선점하지_않아_다음_tick에_다시_발견된다() {
        RecordingFinalizationJobPort jobPort = mock(RecordingFinalizationJobPort.class);
        EnqueueEndedRecordingSessionsUseCase enqueueUseCase = mock(EnqueueEndedRecordingSessionsUseCase.class);
        FinalizeRecordingUseCase useCase = mock(FinalizeRecordingUseCase.class);
        when(jobPort.findDueSessionIds(NOW, 5)).thenReturn(List.of(269L));
        Executor rejecting = command -> {
            throw new RejectedExecutionException();
        };

        scheduler(rejecting, enqueueUseCase, jobPort, useCase).dispatch();

        verify(jobPort, never())
                .tryClaim(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        verify(useCase, never()).finalizeRecording(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void executor가_받은_뒤에만_선점하고_합성을_시작한다() {
        RecordingFinalizationJobPort jobPort = mock(RecordingFinalizationJobPort.class);
        EnqueueEndedRecordingSessionsUseCase enqueueUseCase = mock(EnqueueEndedRecordingSessionsUseCase.class);
        FinalizeRecordingUseCase useCase = mock(FinalizeRecordingUseCase.class);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        Executor capturing = submitted::set;
        FinalizationJobLease lease = new FinalizationJobLease(269L, 1, 0);
        when(jobPort.findDueSessionIds(NOW, 5)).thenReturn(List.of(269L));
        when(jobPort.tryClaim(269L, NOW.plus(Duration.ofHours(5)), NOW)).thenReturn(Optional.of(lease));

        scheduler(capturing, enqueueUseCase, jobPort, useCase).dispatch();
        verify(jobPort, never())
                .tryClaim(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        assertNotNull(submitted.get());
        submitted.get().run();
        verify(jobPort).tryClaim(269L, NOW.plus(Duration.ofHours(5)), NOW);
        verify(useCase).finalizeRecording(lease);
    }

    @Test
    void 후보_조회_실패가_scheduler_스레드_밖으로_전파되지_않는다() {
        RecordingFinalizationJobPort jobPort = mock(RecordingFinalizationJobPort.class);
        EnqueueEndedRecordingSessionsUseCase enqueueUseCase = mock(EnqueueEndedRecordingSessionsUseCase.class);
        FinalizeRecordingUseCase useCase = mock(FinalizeRecordingUseCase.class);
        when(enqueueUseCase.enqueue(5, NOW)).thenThrow(new IllegalStateException("db unavailable"));

        scheduler(Runnable::run, enqueueUseCase, jobPort, useCase).dispatch();

        verify(useCase, never()).finalizeRecording(org.mockito.ArgumentMatchers.any());
    }

    private static RecordingFinalizationScheduler scheduler(
            Executor executor,
            EnqueueEndedRecordingSessionsUseCase enqueueUseCase,
            RecordingFinalizationJobPort jobPort,
            FinalizeRecordingUseCase useCase) {
        var properties = new RecordingFinalizationDispatchProperties(
                true, Duration.ofSeconds(15), 5, Duration.ofHours(5), Duration.ofSeconds(15), Duration.ofMinutes(1), 3);
        return new RecordingFinalizationScheduler(
                executor, enqueueUseCase, jobPort, useCase, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
