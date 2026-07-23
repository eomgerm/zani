package com.a105.zani.session.infrastructure.persistence;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.a105.zani.session.domain.model.SessionMember;
import com.a105.zani.session.domain.repository.SessionMemberRepository;
import com.a105.zani.session.infrastructure.persistence.entity.SessionParticipantJpaEntity;
import com.a105.zani.session.infrastructure.persistence.mapper.SessionMemberPersistenceMapper;
import com.a105.zani.session.infrastructure.persistence.repository.SessionParticipantJpaRepository;

@Component
public class SessionMemberPersistenceAdapter implements SessionMemberRepository {

    private final SessionParticipantJpaRepository sessionParticipantJpaRepository;
    private final SessionMemberPersistenceMapper mapper;

    public SessionMemberPersistenceAdapter(
            SessionParticipantJpaRepository sessionParticipantJpaRepository, SessionMemberPersistenceMapper mapper) {
        this.sessionParticipantJpaRepository = sessionParticipantJpaRepository;
        this.mapper = mapper;
    }

    @Override
    public Optional<SessionMember> findBySessionIdAndUserId(Long sessionId, Long userId) {
        return sessionParticipantJpaRepository
                .findBySessionIdAndMemberId(sessionId, userId)
                .map(mapper::toDomain);
    }

    @Override
    public SessionMember save(SessionMember sessionMember) {
        try {
            SessionParticipantJpaEntity entity = mapper.toEntity(sessionMember);
            SessionParticipantJpaEntity saved = sessionParticipantJpaRepository.saveAndFlush(entity);
            return mapper.toDomain(saved);
        } catch (DataIntegrityViolationException raceLost) {
            // 동시에 같은 (session_id, member_id)로 먼저 들어간 행이 있으면 그걸 그대로 반환한다 (멱등 가입 보장).
            // 주의: 현재 SessionParticipant 엔티티에는 (session_id, member_id) 유니크 제약이 없어
            // 이 방어는 DB 제약이 있을 때만 확실하다. 제약 추가는 ERD 후속 논의 대상.
            return sessionParticipantJpaRepository
                    .findBySessionIdAndMemberId(sessionMember.sessionId(), sessionMember.userId())
                    .map(mapper::toDomain)
                    .orElseThrow(() -> raceLost);
        }
    }
}
