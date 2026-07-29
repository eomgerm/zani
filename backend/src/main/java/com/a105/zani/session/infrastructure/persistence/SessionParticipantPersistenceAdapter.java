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
    public long countBySessionId(Long sessionId) {
        return sessionParticipantJpaRepository.countBySessionId(sessionId);
    }

    @Override
    public SessionParticipant save(SessionParticipant sessionParticipant) {
        try {
            SessionParticipantJpaEntity entity = mapper.toEntity(sessionParticipant);
            SessionParticipantJpaEntity saved = sessionParticipantJpaRepository.saveAndFlush(entity);
            return mapper.toDomain(saved);
        } catch (DataIntegrityViolationException raceLost) {
            // 동시에 같은 (session_id, member_id)로 먼저 들어간 행이 있으면 그걸 그대로 반환한다 (멱등 가입 보장).
            // UK_SESSION_PARTICIPANTS_SESSION_MEMBER 가 이 경합을 DB 수준에서 확정해 준다.
            // 다만 호출자가 트랜잭션 안이면 제약 위반으로 영속성 컨텍스트가 이미 오염돼 이 재조회도 실패할 수 있다.
            // 입장 경로는 세션 행 잠금으로 애초에 경합이 오지 않게 막으므로(SessionJoinService) 여기까지 오지 않는다.
            return sessionParticipantJpaRepository
                    .findBySessionIdAndMemberId(sessionParticipant.sessionId(), sessionParticipant.userId())
                    .map(mapper::toDomain)
                    .orElseThrow(() -> raceLost);
        }
    }
}
