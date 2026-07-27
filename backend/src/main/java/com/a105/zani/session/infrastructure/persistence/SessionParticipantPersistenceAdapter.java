package com.a105.zani.session.infrastructure.persistence;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.infrastructure.persistence.entity.SessionParticipantJpaEntity;
import com.a105.zani.session.infrastructure.persistence.mapper.SessionParticipantPersistenceMapper;
import com.a105.zani.session.infrastructure.persistence.repository.SessionParticipantJpaRepository;

@Component
public class SessionParticipantPersistenceAdapter implements SessionParticipantRepository {

    private final SessionParticipantJpaRepository sessionParticipantJpaRepository;
    private final SessionParticipantPersistenceMapper mapper;

    public SessionParticipantPersistenceAdapter(
            SessionParticipantJpaRepository sessionParticipantJpaRepository,
            SessionParticipantPersistenceMapper mapper) {
        this.sessionParticipantJpaRepository = sessionParticipantJpaRepository;
        this.mapper = mapper;
    }

    @Override
    public Optional<SessionParticipant> findBySessionIdAndUserId(Long sessionId, Long userId) {
        return sessionParticipantJpaRepository
                .findBySessionIdAndMemberId(sessionId, userId)
                .map(mapper::toDomain);
    }

    @Override
    public Optional<SessionParticipant> findById(Long id) {
        return sessionParticipantJpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public java.util.List<SessionParticipant> findBySessionId(Long sessionId) {
        return sessionParticipantJpaRepository.findBySessionIdOrderByIdAsc(sessionId).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public SessionParticipant save(SessionParticipant sessionParticipant) {
        try {
            SessionParticipantJpaEntity entity = mapper.toEntity(sessionParticipant);
            SessionParticipantJpaEntity saved = sessionParticipantJpaRepository.saveAndFlush(entity);
            return mapper.toDomain(saved);
        } catch (DataIntegrityViolationException raceLost) {
            // 동시에 같은 (session_id, member_id)로 먼저 들어간 행이 있으면 그걸 그대로 반환한다 (멱등 가입 보장).
            // 주의: 현재 SessionParticipant 엔티티에는 (session_id, member_id) 유니크 제약이 없어
            // 이 방어는 DB 제약이 있을 때만 확실하다. 제약 추가는 ERD 후속 논의 대상.
            return sessionParticipantJpaRepository
                    .findBySessionIdAndMemberId(sessionParticipant.sessionId(), sessionParticipant.userId())
                    .map(mapper::toDomain)
                    .orElseThrow(() -> raceLost);
        }
    }
}
