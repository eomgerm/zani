package com.a105.zani.postclass.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.postclass.application.exception.PipelineJobUnavailableException;
import com.a105.zani.postclass.application.port.PipelineJobPort;
import com.a105.zani.postclass.domain.model.PipelineStatus;
import com.a105.zani.postclass.infrastructure.persistence.repository.PipelineJobJpaRepository;

/** pipeline_jobs 영속 어댑터. 등록은 INSERT IGNORE 로 session_id UNIQUE 충돌을 호출자 트랜잭션 오염 없이 흡수한다. */
@Component
@RequiredArgsConstructor
public class PipelineJobPersistenceAdapter implements PipelineJobPort {

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
    public Optional<PipelineStatus> findStatusForUpdate(Long sessionId) {
        try {
            return pipelineJobJpaRepository.findStatusForUpdate(sessionId).map(PipelineStatus::valueOf);
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
}
