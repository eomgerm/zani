package com.a105.zani.session.infrastructure.persistence.mapper;

import org.springframework.stereotype.Component;

import com.a105.zani.session.domain.model.MemberRole;
import com.a105.zani.session.domain.model.SessionMember;
import com.a105.zani.session.infrastructure.persistence.entity.SessionParticipantJpaEntity;

@Component
public class SessionMemberPersistenceMapper {

    // ERD 기준: 참여자는 session_participants 테이블에 저장하며,
    // 사용자 식별자는 members.id 를 참조하는 member_id, 역할은 문자열로 저장한다.
    public SessionParticipantJpaEntity toEntity(SessionMember sessionMember) {
        return SessionParticipantJpaEntity.builder()
                .id(sessionMember.id())
                .sessionId(sessionMember.sessionId())
                .memberId(sessionMember.userId())
                .role(sessionMember.role().name())
                .firstJoinedAt(sessionMember.firstJoinedAt())
                .lastAccessedAt(sessionMember.lastAccessedAt())
                .build();
    }

    public SessionMember toDomain(SessionParticipantJpaEntity entity) {
        return SessionMember.reconstitute(
                entity.getId(),
                entity.getSessionId(),
                entity.getMemberId(),
                MemberRole.valueOf(entity.getRole()),
                entity.getFirstJoinedAt(),
                entity.getLastAccessedAt());
    }
}
