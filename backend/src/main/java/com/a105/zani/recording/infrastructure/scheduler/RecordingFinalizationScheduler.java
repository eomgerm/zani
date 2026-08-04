package com.a105.zani.recording.infrastructure.scheduler;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.a105.zani.recording.application.enqueuefinalizations.EnqueueEndedRecordingSessionsUseCase;
import com.a105.zani.recording.application.finalizejob.FinalizationJobLease;
import com.a105.zani.recording.application.finalizejob.RecordingFinalizationJobPort;
import com.a105.zani.recording.application.finalizerecording.FinalizeRecordingUseCase;
import com.a105.zani.recording.infrastructure.config.RecordingFinalizationDispatchProperties;
import com.a105.zani.recording.infrastructure.config.RecordingFinalizationExecutorConfig;

/** 공유 scheduler에서는 조회·제출만 하고 실제 합성은 전용 단일 executor에서 실행한다. */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "recording.finalization.dispatch",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RecordingFinalizationScheduler {

    private final Executor executor;
    private final EnqueueEndedRecordingSessionsUseCase enqueueEndedRecordingSessionsUseCase;
    private final RecordingFinalizationJobPort jobPort;
    private final FinalizeRecordingUseCase finalizeRecordingUseCase;
    private final RecordingFinalizationDispatchProperties properties;
    private final Clock clock;

    public RecordingFinalizationScheduler(
            @Qualifier(RecordingFinalizationExecutorConfig.EXECUTOR) Executor executor,
            EnqueueEndedRecordingSessionsUseCase enqueueEndedRecordingSessionsUseCase,
            RecordingFinalizationJobPort jobPort,
            FinalizeRecordingUseCase finalizeRecordingUseCase,
            RecordingFinalizationDispatchProperties properties,
            Clock clock) {
        this.executor = executor;
        this.enqueueEndedRecordingSessionsUseCase = enqueueEndedRecordingSessionsUseCase;
        this.jobPort = jobPort;
        this.finalizeRecordingUseCase = finalizeRecordingUseCase;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${recording.finalization.dispatch.poll-delay:PT15S}")
    public void dispatch() {
        Instant now = clock.instant();
        List<Long> due;
        try {
            enqueueEndedRecordingSessionsUseCase.enqueue(properties.batchSize(), now);
            due = jobPort.findDueSessionIds(now, properties.batchSize());
        } catch (RuntimeException unavailable) {
            log.warn("Could not discover recording finalization jobs", unavailable);
            return;
        }
        for (Long sessionId : due) {
            try {
                // 선점은 executor가 작업을 받은 뒤에 한다. 제출이 거절돼도 DB는 PENDING 그대로다.
                executor.execute(() -> run(sessionId));
            } catch (RejectedExecutionException saturated) {
                log.debug("Recording finalization executor is busy; deferring remaining jobs");
                return;
            }
        }
    }

    private void run(Long sessionId) {
        try {
            Instant now = clock.instant();
            FinalizationJobLease lease = jobPort.tryClaim(sessionId, now.plus(properties.leaseDuration()), now)
                    .orElse(null);
            if (lease != null) {
                finalizeRecordingUseCase.finalizeRecording(lease);
            }
        } catch (RuntimeException failure) {
            log.error(
                    "Recording finalization orchestration escaped its recorded paths: sessionId={}",
                    sessionId,
                    failure);
        }
    }
}
