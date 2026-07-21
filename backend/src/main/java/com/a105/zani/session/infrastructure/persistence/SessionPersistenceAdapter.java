package com.a105.zani.session.infrastructure.persistence;

import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.repository.SessionRepository;
import com.a105.zani.session.infrastructure.persistence.entity.SessionJpaEntity;
import com.a105.zani.session.infrastructure.persistence.mapper.SessionPersistenceMapper;
import com.a105.zani.session.infrastructure.persistence.repository.SessionJpaRepository;
import org.springframework.stereotype.Component;

@Component
public class SessionPersistenceAdapter implements SessionRepository {

    private final SessionJpaRepository sessionJpaRepository;
    private final SessionPersistenceMapper mapper;

    public SessionPersistenceAdapter(
            SessionJpaRepository sessionJpaRepository,
            SessionPersistenceMapper mapper) {
        this.sessionJpaRepository = sessionJpaRepository;
        this.mapper = mapper;
    }

    @Override
    public Session save(Session session) {
        SessionJpaEntity entity = mapper.toEntity(session);
        SessionJpaEntity saved = sessionJpaRepository.save(entity);
        return mapper.toDomain(saved);
    }
}
