package com.a105.zani.recording.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.recording.application.finalizejob.FinalizationJobLease;
import com.a105.zani.recording.application.finalizejob.FinalizationJobStoreException;
import com.a105.zani.recording.application.finalizejob.RecordingFinalizationJobPort;
import com.a105.zani.recording.domain.model.RecordingFinalizationStatus;
import com.a105.zani.recording.infrastructure.persistence.entity.RecordingFinalizationJobJpaEntity;
import com.a105.zani.recording.infrastructure.persistence.repository.RecordingFinalizationJobJpaRepository;

@Slf4j
@Component
@RequiredArgsConstructor
public class RecordingFinalizationJobPersistenceAdapter implements RecordingFinalizationJobPort {

    private static final int MAX_ERROR_LENGTH = 500;

    private final RecordingFinalizationJobJpaRepository repository;

    @Override
    @Transactional
    public int enqueueEndedSessions(int limit, Instant now) {
        try {
            int inserted = 0;
            for (Long sessionId : repository.findUnqueuedEndedSessionIds(Pageable.ofSize(limit))) {
                inserted += repository.insertIgnore(
                        TsidGenerator.generate(), sessionId, RecordingFinalizationStatus.PENDING.name(), now);
            }
            return inserted;
        } catch (DataAccessException failure) {
            throw new FinalizationJobStoreException(failure);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> findDueSessionIds(Instant now, int limit) {
        try {
            return repository.findDueSessionIds(now, limit);
        } catch (DataAccessException failure) {
            throw new FinalizationJobStoreException(failure);
        }
    }

    @Override
    @Transactional
    public int requeueRunningJobs(Instant now) {
        try {
            return repository.requeueRunningJobs(now);
        } catch (DataAccessException failure) {
            throw new FinalizationJobStoreException(failure);
        }
    }

    @Override
    @Transactional
    public Optional<FinalizationJobLease> tryClaim(Long sessionId, Instant leaseUntil, Instant now) {
        try {
            if (repository.claim(sessionId, leaseUntil, now) != 1) {
                return Optional.empty();
            }
            return repository.findBySessionId(sessionId).map(RecordingFinalizationJobPersistenceAdapter::toLease);
        } catch (DataAccessException failure) {
            throw new FinalizationJobStoreException(failure);
        }
    }

    @Override
    @Transactional
    public Optional<FinalizationJobLease> beginAttempt(FinalizationJobLease lease, Instant now) {
        try {
            if (repository.beginAttempt(lease.sessionId(), lease.leaseToken(), now) != 1) {
                lostLease(lease);
                return Optional.empty();
            }
            return repository
                    .findBySessionId(lease.sessionId())
                    .map(RecordingFinalizationJobPersistenceAdapter::toLease);
        } catch (DataAccessException failure) {
            throw new FinalizationJobStoreException(failure);
        }
    }

    @Override
    @Transactional
    public boolean markWaiting(FinalizationJobLease lease, Instant nextAttemptAt, Instant now) {
        try {
            return checkLease(lease, repository.markWaiting(lease.sessionId(), lease.leaseToken(), nextAttemptAt, now));
        } catch (DataAccessException failure) {
            throw new FinalizationJobStoreException(failure);
        }
    }

    @Override
    @Transactional
    public boolean markContended(FinalizationJobLease lease, Instant nextAttemptAt, Instant now) {
        try {
            return checkLease(
                    lease, repository.markContended(lease.sessionId(), lease.leaseToken(), nextAttemptAt, now));
        } catch (DataAccessException failure) {
            throw new FinalizationJobStoreException(failure);
        }
    }

    @Override
    @Transactional
    public boolean markRetry(FinalizationJobLease lease, String error, Instant nextAttemptAt, Instant now) {
        try {
            return checkLease(
                    lease,
                    repository.markRetry(lease.sessionId(), lease.leaseToken(), truncate(error), nextAttemptAt, now));
        } catch (DataAccessException failure) {
            throw new FinalizationJobStoreException(failure);
        }
    }

    @Override
    @Transactional
    public boolean markFailed(FinalizationJobLease lease, String error, Instant now) {
        try {
            return checkLease(
                    lease, repository.markFailed(lease.sessionId(), lease.leaseToken(), truncate(error), now));
        } catch (DataAccessException failure) {
            throw new FinalizationJobStoreException(failure);
        }
    }

    @Override
    @Transactional
    public boolean markCompleted(
            FinalizationJobLease lease,
            String manifestPath,
            String outputPath,
            long outputSizeBytes,
            String outputSha256,
            Instant now) {
        try {
            return checkLease(
                    lease,
                    repository.markCompleted(
                            lease.sessionId(),
                            lease.leaseToken(),
                            manifestPath,
                            outputPath,
                            outputSizeBytes,
                            outputSha256,
                            now));
        } catch (DataAccessException failure) {
            throw new FinalizationJobStoreException(failure);
        }
    }

    private static FinalizationJobLease toLease(RecordingFinalizationJobJpaEntity entity) {
        return new FinalizationJobLease(entity.getSessionId(), entity.getLeaseToken(), entity.getAttemptCount());
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }

    private boolean checkLease(FinalizationJobLease lease, int updated) {
        if (updated == 1) {
            return true;
        }
        lostLease(lease);
        return false;
    }

    private void lostLease(FinalizationJobLease lease) {
        log.warn(
                "Discarding a recording finalization result that lost its lease: sessionId={}, leaseToken={}",
                lease.sessionId(),
                lease.leaseToken());
    }
}
