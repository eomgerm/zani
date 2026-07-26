package com.a105.zani.session.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.a105.zani.session.application.exception.DuplicateInviteCodeException;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.domain.repository.SessionRepository;
import com.a105.zani.session.infrastructure.persistence.entity.SessionJpaEntity;
import com.a105.zani.session.infrastructure.persistence.mapper.SessionPersistenceMapper;
import com.a105.zani.session.infrastructure.persistence.repository.SessionJpaRepository;

@Component
public class SessionPersistenceAdapter implements SessionRepository {

    private final SessionJpaRepository sessionJpaRepository;
    private final SessionPersistenceMapper mapper;

    public SessionPersistenceAdapter(SessionJpaRepository sessionJpaRepository, SessionPersistenceMapper mapper) {
        this.sessionJpaRepository = sessionJpaRepository;
        this.mapper = mapper;
    }

    @Override
    public Session save(Session session) {
        SessionJpaEntity entity = mapper.toEntity(session);
        try {
            SessionJpaEntity saved = sessionJpaRepository.saveAndFlush(entity);
            return mapper.toDomain(saved);
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateInviteCodeException(exception);
        }
    }

    @Override
    public List<Session> findLiveStartedBefore(Instant startedBefore, int limit) {
        return sessionJpaRepository
                .findByStatusAndStartedAtLessThanEqualOrderByStartedAtAsc(
                        SessionStatus.LIVE.name(), startedBefore, PageRequest.of(0, limit))
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public Optional<Session> findById(Long id) {
        return sessionJpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Optional<Session> findByInviteCode(String inviteCode) {
        return sessionJpaRepository.findByInviteCode(inviteCode).map(mapper::toDomain);
    }
}
