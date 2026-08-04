package com.a105.zani.recording.application.checkfinalizationreadiness;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.session.application.checkended.CheckSessionEndedUseCase;

/** 세션 종료와 전체 Track Egress/outbox 종결 여부를 함께 판정한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class GetSessionFinalizationReadinessService implements GetSessionFinalizationReadinessUseCase {

    private final GetSessionFinalizationReadinessQueryPort readinessQueryPort;
    private final CheckSessionEndedUseCase checkSessionEndedUseCase;

    @Override
    @Transactional(readOnly = true)
    public FinalizationReadiness check(Long sessionId) {
        if (!checkSessionEndedUseCase.isEnded(sessionId)) {
            log.info("Session is not ended, recording finalization must wait: sessionId={}", sessionId);
            return FinalizationReadiness.IN_PROGRESS;
        }

        GetSessionFinalizationReadinessQueryPort.FinalizationProgress progress = readinessQueryPort.load(sessionId);
        if (progress.stillRunning()) {
            log.info(
                    "Recording finalization inputs are still running: sessionId={}, recordings={}, outbox={}",
                    sessionId,
                    progress.unfinishedRecordings(),
                    progress.undeliveredOutbox());
            return FinalizationReadiness.IN_PROGRESS;
        }
        if (progress.permanentlyBroken()) {
            log.error(
                    "Recording finalization inputs are permanently broken: sessionId={}, recordings={}, outbox={}",
                    sessionId,
                    progress.failedRecordings(),
                    progress.failedOutbox());
            return FinalizationReadiness.BROKEN;
        }
        return FinalizationReadiness.SETTLED;
    }
}
