package com.a105.zani.session.infrastructure.persistence.mapper;

import com.a105.zani.session.domain.model.SessionMember;
import com.a105.zani.session.infrastructure.persistence.entity.SessionMemberJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class SessionMemberPersistenceMapper {

    public SessionMemberJpaEntity toEntity(SessionMember sessionMember) {
        return new SessionMemberJpaEntity(
                sessionMember.id(),
                sessionMember.sessionId(),
                sessionMember.userId(),
                sessionMember.role(),
                sessionMember.firstJoinedAt(),
                sessionMember.lastAccessedAt());
    }

    public SessionMember toDomain(SessionMemberJpaEntity entity) {
        return SessionMember.reconstitute(
                entity.getId(),
                entity.getSessionId(),
                entity.getUserId(),
                entity.getRole(),
                entity.getFirstJoinedAt(),
                entity.getLastAccessedAt());
    }
}
