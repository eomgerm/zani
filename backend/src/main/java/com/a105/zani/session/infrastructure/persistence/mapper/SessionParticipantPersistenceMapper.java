package com.a105.zani.session.infrastructure.persistence.mapper;

import org.springframework.stereotype.Component;

import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.infrastructure.persistence.entity.SessionParticipantJpaEntity;

@Component
public class SessionParticipantPersistenceMapper {

    // ERD 기준: 참여자는 session_participants 테이블에 저장하며,
    // 사용자 식별자는 members.id 를 참조하는 member_id, 역할은 enum 이름으로 저장한다.
    public SessionParticipantJpaEntity toEntity(SessionParticipant sessionParticipant) {
        return SessionParticipantJpaEntity.builder()
                .id(sessionParticipant.id())
                .sessionId(sessionParticipant.sessionId())
                .memberId(sessionParticipant.userId())
                .role(sessionParticipant.role())
                .firstJoinedAt(sessionParticipant.firstJoinedAt())
                .lastJoinedAt(sessionParticipant.lastJoinedAt())
                .lastLeftAt(sessionParticipant.lastLeftAt())
                .lastAccessedAt(sessionParticipant.lastAccessedAt())
                .build();
    }

    public SessionParticipant toDomain(SessionParticipantJpaEntity entity) {
        return SessionParticipant.reconstitute(
                entity.getId(),
                entity.getSessionId(),
                entity.getMemberId(),
                entity.getRole(),
                entity.getFirstJoinedAt(),
                entity.getLastJoinedAt(),
                entity.getLastLeftAt(),
                entity.getLastAccessedAt());
    }
}
