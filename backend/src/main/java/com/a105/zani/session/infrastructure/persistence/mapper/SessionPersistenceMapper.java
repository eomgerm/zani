package com.a105.zani.session.infrastructure.persistence.mapper;

import org.springframework.stereotype.Component;

import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.infrastructure.persistence.entity.SessionJpaEntity;

@Component
public class SessionPersistenceMapper {

    public SessionJpaEntity toEntity(Session session) {
        // ERD 기준: 강사는 members.id 를 참조하는 host_member_id 로 저장한다.
        // limited_mode 컬럼은 ERD 에 없어 저장하지 않는다.
        return SessionJpaEntity.builder()
                .id(session.id())
                .hostMemberId(session.instructorId())
                .title(session.title())
                .inviteCode(session.inviteCode())
                .status(session.status())
                .analysisStatus(session.analysisStatus())
                .startedAt(session.startedAt())
                .endedAt(session.endedAt())
                .endReason(session.endReason())
                .build();
    }

    public Session toDomain(SessionJpaEntity entity) {
        return Session.reconstitute(
                entity.getId(),
                entity.getHostMemberId(),
                entity.getTitle(),
                entity.getInviteCode(),
                false,
                entity.getStatus(),
                entity.getAnalysisStatus(),
                entity.getStartedAt(),
                entity.getEndedAt(),
                entity.getEndReason());
    }
}
