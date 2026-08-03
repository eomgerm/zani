package com.a105.zani.postclass.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.postclass.application.exception.PipelineJobUnavailableException;
import com.a105.zani.postclass.application.port.PipelineJobPort;
import com.a105.zani.postclass.application.port.PipelineJobState;
import com.a105.zani.postclass.domain.model.PipelineStatus;
import com.a105.zani.postclass.infrastructure.persistence.repository.PipelineJobJpaRepository;

/** pipeline_jobs 영속 어댑터. 등록은 INSERT IGNORE 로 session_id UNIQUE 충돌을 호출자 트랜잭션 오염 없이 흡수한다. */
@Component
@RequiredArgsConstructor
public class PipelineJobPersistenceAdapter implements PipelineJobPort {

    /** last_error 컬럼 길이(V11). */
    private static final int MAX_ERROR_LENGTH = 500;

    private final PipelineJobJpaRepository pipelineJobJpaRepository;

    @Override
    public boolean enqueue(Long sessionId, Instant queuedAt) {
        try {
            return pipelineJobJpaRepository.insertIgnore(TsidGenerator.generate(), sessionId, queuedAt) == 1;
        } catch (DataAccessException exception) {
            throw new PipelineJobUnavailableException(exception);
        }
    }

    @Override
    public Optional<PipelineJobState> findForUpdate(Long sessionId) {
        try {
            return pipelineJobJpaRepository
                    .findForUpdate(sessionId)
                    .map(job -> new PipelineJobState(
                            PipelineStatus.valueOf(job.getStatus()),
                            job.getAttemptCount(),
                            job.getCreatedAt(),
                            job.getNextAttemptAt()));
        } catch (DataAccessException exception) {
            throw new PipelineJobUnavailableException(exception);
        }
    }

    @Override
    public void updateStatus(Long sessionId, PipelineStatus status, Instant changedAt) {
        try {
            pipelineJobJpaRepository.updateStatus(sessionId, status.name(), changedAt);
        } catch (DataAccessException exception) {
            throw new PipelineJobUnavailableException(exception);
        }
    }

    @Override
    public void markRetry(Long sessionId, String error, Instant nextAttemptAt, Instant changedAt) {
        try {
            pipelineJobJpaRepository.markRetry(sessionId, truncate(error), nextAttemptAt, changedAt);
        } catch (DataAccessException exception) {
            throw new PipelineJobUnavailableException(exception);
        }
    }

    @Override
    public void markFailed(Long sessionId, String error, Instant changedAt) {
        try {
            pipelineJobJpaRepository.markFailed(sessionId, truncate(error), changedAt);
        } catch (DataAccessException exception) {
            throw new PipelineJobUnavailableException(exception);
        }
    }

    @Override
    public List<Long> findDueTranscriptionSessionIds(Instant now, int limit) {
        try {
            return pipelineJobJpaRepository.findDueTranscriptionSessionIds(now, limit);
        } catch (DataAccessException exception) {
            throw new PipelineJobUnavailableException(exception);
        }
    }

    @Override
    public void clearRetryWait(Long sessionId, Instant changedAt) {
        try {
            pipelineJobJpaRepository.clearRetryWait(sessionId, changedAt);
        } catch (DataAccessException exception) {
            throw new PipelineJobUnavailableException(exception);
        }
    }

    @Override
    public List<Long> findOverdueSessionIds(Instant queuedBefore, int limit) {
        try {
            return pipelineJobJpaRepository.findOverdueSessionIds(queuedBefore, limit);
        } catch (DataAccessException exception) {
            throw new PipelineJobUnavailableException(exception);
        }
    }

    /**
     * last_error 컬럼 길이에 맞춘다. 자르지 않으면 긴 스택 메시지가 들어올 때 UPDATE 가 통째로 실패하는데, 그러면 실패를 기록하려다 실패해 작업이 재시도 대기에도 최종 실패에도 들어가지
     * 못하고 멈춘다.
     */
    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }
}
