package com.a105.zani.session.infrastructure.persistence.mapper;

import org.springframework.stereotype.Component;

import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.infrastructure.persistence.entity.SessionJpaEntity;

@Component
public class SessionPersistenceMapper {

    // 저장은 ID가 지정된 엔티티의 merge 라서 여기서 채우지 않은 updatable 컬럼은 매 저장마다 NULL 로 덮인다.
    // 도메인이 소유하는 상태(종료 시각·사유, 메모 마감)는 반드시 함께 실어야 한다.
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
                .noteDueAt(session.noteDueAt())
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
                entity.getStatus(),
                entity.getAnalysisStatus(),
                entity.getEndedAt(),
                entity.getNoteDueAt(),
                entity.getEndReason());
    }
}
