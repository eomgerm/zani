package com.a105.zani.session.infrastructure.persistence;

import org.springframework.stereotype.Component;

import com.a105.zani.session.domain.model.SessionStatusChange;
import com.a105.zani.session.domain.repository.SessionStatusChangeRepository;
import com.a105.zani.session.infrastructure.persistence.entity.SessionStatusChangeJpaEntity;
import com.a105.zani.session.infrastructure.persistence.repository.SessionStatusChangeJpaRepository;

@Component
public class SessionStatusChangePersistenceAdapter implements SessionStatusChangeRepository {

    private final SessionStatusChangeJpaRepository sessionStatusChangeJpaRepository;

    public SessionStatusChangePersistenceAdapter(SessionStatusChangeJpaRepository sessionStatusChangeJpaRepository) {
        this.sessionStatusChangeJpaRepository = sessionStatusChangeJpaRepository;
    }

    @Override
    public void append(SessionStatusChange statusChange) {
        sessionStatusChangeJpaRepository.save(SessionStatusChangeJpaEntity.builder()
                .id(statusChange.id())
                .sessionId(statusChange.sessionId())
                .fromStatus(statusChange.fromStatus())
                .toStatus(statusChange.toStatus())
                .changedAt(statusChange.changedAt())
                .build());
    }
}
