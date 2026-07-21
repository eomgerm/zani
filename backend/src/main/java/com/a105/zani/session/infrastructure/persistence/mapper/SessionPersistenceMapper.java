package com.a105.zani.session.infrastructure.persistence.mapper;

import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.infrastructure.persistence.entity.SessionJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class SessionPersistenceMapper {

    public SessionJpaEntity toEntity(Session session) {
        return new SessionJpaEntity(
                session.id(),
                session.instructorId(),
                session.title(),
                session.inviteCode(),
                session.status(),
                session.limitedMode(),
                session.startedAt());
    }

    public Session toDomain(SessionJpaEntity entity) {
        return Session.reconstitute(
                entity.getId(),
                entity.getInstructorId(),
                entity.getTitle(),
                entity.getInviteCode(),
                entity.isLimitedMode(),
                entity.getStartedAt(),
                entity.getStatus());
    }
}
