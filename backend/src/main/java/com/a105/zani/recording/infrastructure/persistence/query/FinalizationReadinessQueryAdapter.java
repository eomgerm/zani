package com.a105.zani.recording.infrastructure.persistence.query;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.recording.application.checkfinalizationreadiness.GetSessionFinalizationReadinessQueryPort;
import com.a105.zani.recording.application.port.RecordingOutboxType;
import com.a105.zani.recording.domain.model.RecordingStatus;
import com.a105.zani.recording.infrastructure.persistence.entity.RecordingOutboxJpaEntity;
import com.a105.zani.recording.infrastructure.persistence.repository.RecordingJpaRepository;
import com.a105.zani.recording.infrastructure.persistence.repository.RecordingOutboxJpaRepository;

/** 최종 합성 대상 전체 Track Egress와 START_TRACK_EGRESS outbox 상태를 집계한다. */
@Component
@RequiredArgsConstructor
public class FinalizationReadinessQueryAdapter implements GetSessionFinalizationReadinessQueryPort {

    private static final List<String> RUNNING_RECORDING_STATUSES =
            List.of(RecordingStatus.STARTING.name(), RecordingStatus.RECORDING.name());
    private static final List<String> FAILED_RECORDING_STATUSES = List.of(RecordingStatus.FAILED.name());
    private static final List<String> UNDELIVERED_OUTBOX_STATUSES =
            List.of(RecordingOutboxJpaEntity.STATUS_PENDING, RecordingOutboxJpaEntity.STATUS_IN_PROGRESS);
    private static final List<String> FAILED_OUTBOX_STATUSES = List.of(RecordingOutboxJpaEntity.STATUS_FAILED);

    private final RecordingJpaRepository recordingJpaRepository;
    private final RecordingOutboxJpaRepository recordingOutboxJpaRepository;

    @Override
    @Transactional(readOnly = true)
    public FinalizationProgress load(Long sessionId) {
        return new FinalizationProgress(
                recordingJpaRepository.countBySessionIdAndStatusIn(sessionId, RUNNING_RECORDING_STATUSES),
                recordingJpaRepository.countBySessionIdAndStatusIn(sessionId, FAILED_RECORDING_STATUSES),
                countOutbox(sessionId, UNDELIVERED_OUTBOX_STATUSES),
                countOutbox(sessionId, FAILED_OUTBOX_STATUSES));
    }

    private long countOutbox(Long sessionId, List<String> statuses) {
        return recordingOutboxJpaRepository.countBySessionIdAndOutboxTypeAndStatusIn(
                sessionId, RecordingOutboxType.START_TRACK_EGRESS.name(), statuses);
    }
}
