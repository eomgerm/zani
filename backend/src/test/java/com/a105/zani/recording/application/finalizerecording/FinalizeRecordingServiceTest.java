package com.a105.zani.recording.application.finalizerecording;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.a105.zani.recording.application.checkfinalizationreadiness.FinalizationReadiness;
import com.a105.zani.recording.application.checkfinalizationreadiness.GetSessionFinalizationReadinessUseCase;
import com.a105.zani.recording.application.finalizejob.FinalizationJobLease;
import com.a105.zani.recording.application.finalizejob.RecordingFinalizationJobPort;
import com.a105.zani.recording.application.finalizemanifest.BuildFinalizationManifestUseCase;
import com.a105.zani.recording.application.finalizemanifest.FinalizationManifestStorePort;
import com.a105.zani.recording.application.finalizeworker.FinalizationWorkerPort;
import com.a105.zani.recording.application.finalizeworker.FinalizationWorkerResult;
import com.a105.zani.recording.domain.exception.InvalidRecordingManifestException;
import com.a105.zani.recording.domain.model.RecordingManifest;
import com.a105.zani.recording.domain.model.RecordingTrackEntry;
import com.a105.zani.recording.domain.model.TrackSource;
import com.a105.zani.recording.infrastructure.config.RecordingFinalizationDispatchProperties;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinalizeRecordingServiceTest {

    private static final Long SESSION_ID = 269L;
    private static final Instant NOW = Instant.parse("2026-08-04T10:00:00Z");
    private static final FinalizationJobLease CLAIMED = new FinalizationJobLease(SESSION_ID, 1, 0);

    @Mock
    private GetSessionFinalizationReadinessUseCase readinessUseCase;

    @Mock
    private BuildFinalizationManifestUseCase buildManifestUseCase;

    @Mock
    private FinalizationManifestStorePort manifestStorePort;

    @Mock
    private FinalizationWorkerPort workerPort;

    @Mock
    private RecordingFinalizationJobPort jobPort;

    private FinalizeRecordingService service;

    @BeforeEach
    void setUp() {
        var properties = new RecordingFinalizationDispatchProperties(
                true, Duration.ofSeconds(15), 5, Duration.ofHours(5), Duration.ofSeconds(15), Duration.ofMinutes(1), 3);
        service = new FinalizeRecordingService(
                readinessUseCase,
                buildManifestUseCase,
                manifestStorePort,
                workerPort,
                jobPort,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void Egress가_정리_중이면_worker_attempt를_시작하지_않고_대기한다() {
        when(readinessUseCase.check(SESSION_ID)).thenReturn(FinalizationReadiness.IN_PROGRESS);

        service.finalizeRecording(CLAIMED);

        verify(jobPort).markWaiting(CLAIMED, NOW.plusSeconds(15), NOW);
        verify(jobPort, never()).beginAttempt(any(), any());
        verify(workerPort, never()).finalizeRecording(any());
    }

    @Test
    void Egress가_영구_실패하면_worker를_호출하지_않고_최종_실패한다() {
        when(readinessUseCase.check(SESSION_ID)).thenReturn(FinalizationReadiness.BROKEN);

        service.finalizeRecording(CLAIMED);

        verify(jobPort).markFailed(CLAIMED, "recordings_broken", NOW);
        verify(jobPort, never()).beginAttempt(any(), any());
    }

    @Test
    void manifest를_저장한_뒤_worker를_실행하고_MP4_메타데이터를_확정한다() {
        FinalizationJobLease attempted = attempted(1);
        RecordingManifest manifest = manifest();
        when(readinessUseCase.check(SESSION_ID)).thenReturn(FinalizationReadiness.SETTLED);
        when(jobPort.beginAttempt(CLAIMED, NOW)).thenReturn(Optional.of(attempted));
        when(buildManifestUseCase.build(SESSION_ID)).thenReturn(manifest);
        when(workerPort.finalizeRecording(SESSION_ID))
                .thenReturn(FinalizationWorkerResult.success("manifest", "output", 1234L, "a".repeat(64)));

        service.finalizeRecording(CLAIMED);

        InOrder order = inOrder(manifestStorePort, workerPort, jobPort);
        order.verify(manifestStorePort).store(SESSION_ID, manifest);
        order.verify(workerPort).finalizeRecording(SESSION_ID);
        order.verify(jobPort).markCompleted(attempted, "manifest", "output", 1234L, "a".repeat(64), NOW);
    }

    @Test
    void exit_9_경합은_실패나_재시도로_세지_않는다() {
        FinalizationJobLease attempted = prepareWorker(1, FinalizationWorkerResult.exited(9, "m", "o"));

        service.finalizeRecording(CLAIMED);

        verify(jobPort).markContended(attempted, NOW.plusSeconds(15), NOW);
        verify(jobPort, never()).markRetry(any(), any(), any(), any());
        verify(jobPort, never()).markFailed(any(), any(), any());
    }

    @Test
    void exit_5는_상한_전에는_재시도하고_상한에서는_최종_실패한다() {
        FinalizationJobLease first = prepareWorker(1, FinalizationWorkerResult.exited(5, "m", "o"));
        service.finalizeRecording(CLAIMED);
        verify(jobPort).markRetry(first, "worker_exit_5", NOW.plusSeconds(60), NOW);

        FinalizationJobLease last = prepareWorker(3, FinalizationWorkerResult.exited(5, "m", "o"));
        service.finalizeRecording(CLAIMED);
        verify(jobPort).markFailed(last, "worker_exit_5", NOW);
    }

    @Test
    void exit_2_manifest_오류는_즉시_최종_실패한다() {
        FinalizationJobLease attempted = prepareWorker(1, FinalizationWorkerResult.exited(2, "m", "o"));

        service.finalizeRecording(CLAIMED);

        verify(jobPort).markFailed(attempted, "worker_exit_2", NOW);
        verify(jobPort, never()).markRetry(eq(attempted), any(), any(), any());
    }

    @Test
    void manifest_계약_위반은_worker를_호출하지_않고_최종_실패한다() {
        FinalizationJobLease attempted = attempted(1);
        when(readinessUseCase.check(SESSION_ID)).thenReturn(FinalizationReadiness.SETTLED);
        when(jobPort.beginAttempt(CLAIMED, NOW)).thenReturn(Optional.of(attempted));
        when(buildManifestUseCase.build(SESSION_ID)).thenThrow(new InvalidRecordingManifestException());

        service.finalizeRecording(CLAIMED);

        verify(jobPort).markFailed(attempted, "manifest_invalid", NOW);
        verify(workerPort, never()).finalizeRecording(any());
    }

    private FinalizationJobLease prepareWorker(int attemptCount, FinalizationWorkerResult result) {
        FinalizationJobLease attempted = attempted(attemptCount);
        RecordingManifest manifest = manifest();
        when(readinessUseCase.check(SESSION_ID)).thenReturn(FinalizationReadiness.SETTLED);
        when(jobPort.beginAttempt(CLAIMED, NOW)).thenReturn(Optional.of(attempted));
        when(buildManifestUseCase.build(SESSION_ID)).thenReturn(manifest);
        when(workerPort.finalizeRecording(SESSION_ID)).thenReturn(result);
        return attempted;
    }

    private static FinalizationJobLease attempted(int attemptCount) {
        return new FinalizationJobLease(SESSION_ID, 1, attemptCount);
    }

    private static RecordingManifest manifest() {
        return RecordingManifest.create(
                String.valueOf(SESSION_ID),
                NOW.minusSeconds(3_600),
                List.of(new RecordingTrackEntry(
                        "instructor",
                        SessionParticipantRole.INSTRUCTOR,
                        TrackSource.CAMERA,
                        "raw/camera.webm",
                        0,
                        60_000,
                        null)));
    }
}
