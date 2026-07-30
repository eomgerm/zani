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
                // 이 줄이 빠져 있어 종료 시각이 저장되지 않았다. save() 가 분리된 엔티티를 새로 빌드해 병합하므로,
                // 여기서 옮기지 않으면 도메인이 찍은 값이 NULL 로 덮인다.
                .endedAt(session.endedAt())
                .build();
    }

    public Session toDomain(SessionJpaEntity entity) {
        return Session.reconstitute(
                entity.getId(),
                entity.getHostMemberId(),
                entity.getTitle(),
                entity.getInviteCode(),
                false,
                entity.getStartedAt(),
                entity.getEndedAt(),
                entity.getStatus(),
                entity.getAnalysisStatus());
    }
}
