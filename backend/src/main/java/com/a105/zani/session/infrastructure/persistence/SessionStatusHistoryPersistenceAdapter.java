package com.a105.zani.session.infrastructure.persistence;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.session.application.port.SessionStatusHistoryPort;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.infrastructure.persistence.entity.SessionStatusChangeJpaEntity;
import com.a105.zani.session.infrastructure.persistence.repository.SessionStatusChangeJpaRepository;

@Component
public class SessionStatusHistoryPersistenceAdapter implements SessionStatusHistoryPort {

    private final SessionStatusChangeJpaRepository repository;

    public SessionStatusHistoryPersistenceAdapter(SessionStatusChangeJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void record(long sessionId, SessionStatus from, SessionStatus to, Instant changedAt) {
        repository.save(SessionStatusChangeJpaEntity.builder()
                .id(TsidGenerator.generate())
                .sessionId(sessionId)
                .fromStatus(from)
                .toStatus(to)
                .changedAt(changedAt)
                .build());
    }
}
