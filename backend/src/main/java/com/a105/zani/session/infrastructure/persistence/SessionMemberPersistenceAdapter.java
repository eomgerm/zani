package com.a105.zani.session.infrastructure.persistence;

import com.a105.zani.session.domain.model.SessionMember;
import com.a105.zani.session.domain.repository.SessionMemberRepository;
import com.a105.zani.session.infrastructure.persistence.entity.SessionMemberJpaEntity;
import com.a105.zani.session.infrastructure.persistence.mapper.SessionMemberPersistenceMapper;
import com.a105.zani.session.infrastructure.persistence.repository.SessionMemberJpaRepository;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class SessionMemberPersistenceAdapter implements SessionMemberRepository {

    private final SessionMemberJpaRepository sessionMemberJpaRepository;
    private final SessionMemberPersistenceMapper mapper;

    public SessionMemberPersistenceAdapter(
            SessionMemberJpaRepository sessionMemberJpaRepository,
            SessionMemberPersistenceMapper mapper) {
        this.sessionMemberJpaRepository = sessionMemberJpaRepository;
        this.mapper = mapper;
    }

    @Override
    public Optional<SessionMember> findBySessionIdAndUserId(Long sessionId, Long userId) {
        return sessionMemberJpaRepository.findBySessionIdAndUserId(sessionId, userId).map(mapper::toDomain);
    }

    @Override
    public SessionMember save(SessionMember sessionMember) {
        try {
            SessionMemberJpaEntity entity = mapper.toEntity(sessionMember);
            SessionMemberJpaEntity saved = sessionMemberJpaRepository.saveAndFlush(entity);
            return mapper.toDomain(saved);
        } catch (DataIntegrityViolationException raceLost) {
            // 동시에 같은 (session_id, user_id)로 먼저 들어간 행이 있으면 그걸 그대로 반환한다 (멱등 가입 보장).
            return sessionMemberJpaRepository
                    .findBySessionIdAndUserId(sessionMember.sessionId(), sessionMember.userId())
                    .map(mapper::toDomain)
                    .orElseThrow(() -> raceLost);
        }
    }
}
