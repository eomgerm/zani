package com.a105.zani.recording.application.finalizerecording;

import java.time.Clock;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.recording.application.checkfinalizationreadiness.FinalizationReadiness;
import com.a105.zani.recording.application.checkfinalizationreadiness.GetSessionFinalizationReadinessUseCase;
import com.a105.zani.recording.application.finalizejob.FinalizationJobLease;
import com.a105.zani.recording.application.finalizejob.RecordingFinalizationJobPort;
import com.a105.zani.recording.application.finalizemanifest.BuildFinalizationManifestUseCase;
import com.a105.zani.recording.application.finalizemanifest.FinalizationManifestStoreException;
import com.a105.zani.recording.application.finalizemanifest.FinalizationManifestStorePort;
import com.a105.zani.recording.application.finalizeworker.FinalizationWorkerOutcome;
import com.a105.zani.recording.application.finalizeworker.FinalizationWorkerPort;
import com.a105.zani.recording.application.finalizeworker.FinalizationWorkerResult;
import com.a105.zani.recording.domain.exception.ForbiddenStudentCameraTrackException;
import com.a105.zani.recording.domain.exception.InvalidRecordingManifestException;
import com.a105.zani.recording.domain.exception.InvalidRecordingTrackException;
import com.a105.zani.recording.domain.model.RecordingManifest;
import com.a105.zani.recording.infrastructure.config.RecordingFinalizationDispatchProperties;
import com.a105.zani.session.application.exception.SessionNotFoundException;

/** 준비 판정부터 manifest·worker·결과 기록까지 잇되, 긴 파일 작업을 어떤 DB 트랜잭션으로도 감싸지 않는다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinalizeRecordingService implements FinalizeRecordingUseCase {

    private final GetSessionFinalizationReadinessUseCase readinessUseCase;
    private final BuildFinalizationManifestUseCase buildManifestUseCase;
    private final FinalizationManifestStorePort manifestStorePort;
    private final FinalizationWorkerPort workerPort;
    private final RecordingFinalizationJobPort jobPort;
    private final RecordingFinalizationDispatchProperties properties;
    private final Clock clock;

    @Override
    public void finalizeRecording(FinalizationJobLease claimedLease) {
        Long sessionId = claimedLease.sessionId();
        FinalizationReadiness readiness = readinessUseCase.check(sessionId);
        if (readiness == FinalizationReadiness.IN_PROGRESS) {
            Instant now = clock.instant();
            jobPort.markWaiting(claimedLease, now.plus(properties.waitingDelay()), now);
            return;
        }
        if (readiness == FinalizationReadiness.BROKEN) {
            jobPort.markFailed(claimedLease, "recordings_broken", clock.instant());
            return;
        }

        FinalizationJobLease attempted =
                jobPort.beginAttempt(claimedLease, clock.instant()).orElse(null);
        if (attempted == null) {
            return;
        }
        try {
            RecordingManifest manifest = buildManifestUseCase.build(sessionId);
            manifestStorePort.store(sessionId, manifest);
            handleWorkerResult(attempted, workerPort.finalizeRecording(sessionId));
        } catch (InvalidRecordingManifestException
                | InvalidRecordingTrackException
                | ForbiddenStudentCameraTrackException
                | SessionNotFoundException invalid) {
            jobPort.markFailed(attempted, "manifest_invalid", clock.instant());
        } catch (FinalizationManifestStoreException unavailable) {
            retryOrFail(attempted, "manifest_store_failed");
        } catch (RuntimeException unexpected) {
            log.error("Unexpected recording finalization failure: sessionId={}", sessionId, unexpected);
            retryOrFail(attempted, "finalization_unexpected_failure");
        }
    }

    private void handleWorkerResult(FinalizationJobLease lease, FinalizationWorkerResult result) {
        if (result.outcome() == FinalizationWorkerOutcome.SUCCESS) {
            jobPort.markCompleted(
                    lease,
                    result.manifestPath(),
                    result.outputPath(),
                    result.outputSizeBytes(),
                    result.outputSha256(),
                    clock.instant());
            return;
        }
        if (result.outcome() == FinalizationWorkerOutcome.CONTENDED) {
            Instant now = clock.instant();
            jobPort.markContended(lease, now.plus(properties.waitingDelay()), now);
            return;
        }
        String reason = result.exitCode() == null
                ? "worker_" + result.outcome().name().toLowerCase(java.util.Locale.ROOT)
                : "worker_exit_" + result.exitCode();
        if (isRetryable(result)) {
            retryOrFail(lease, reason);
        } else {
            jobPort.markFailed(lease, reason, clock.instant());
        }
    }

    private static boolean isRetryable(FinalizationWorkerResult result) {
        if (result.outcome() == FinalizationWorkerOutcome.TIMED_OUT
                || result.outcome() == FinalizationWorkerOutcome.START_FAILED) {
            return true;
        }
        return result.outcome() == FinalizationWorkerOutcome.FAILED
                && (Integer.valueOf(5).equals(result.exitCode())
                        || Integer.valueOf(6).equals(result.exitCode()));
    }

    private void retryOrFail(FinalizationJobLease lease, String reason) {
        Instant now = clock.instant();
        if (lease.attemptCount() >= properties.maxAttempts()) {
            jobPort.markFailed(lease, reason, now);
            return;
        }
        int exponent = Math.max(0, Math.min(lease.attemptCount() - 1, 6));
        long multiplier = 1L << exponent;
        jobPort.markRetry(lease, reason, now.plus(properties.retryDelay().multipliedBy(multiplier)), now);
    }
}
