package com.a105.zani.session.infrastructure.persistence.query;

import com.a105.zani.session.application.getsessionlist.GetSessionListQueryPort;
import com.a105.zani.session.application.getsessionlist.SessionSummaryResult;
import com.a105.zani.session.domain.model.MemberRole;
import com.a105.zani.session.infrastructure.persistence.entity.SessionJpaEntity;
import com.a105.zani.session.infrastructure.persistence.entity.SessionMemberJpaEntity;
import com.a105.zani.session.infrastructure.persistence.repository.SessionJpaRepository;
import com.a105.zani.session.infrastructure.persistence.repository.SessionMemberJpaRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class SessionListQueryAdapter implements GetSessionListQueryPort {

    private final SessionJpaRepository sessionJpaRepository;
    private final SessionMemberJpaRepository sessionMemberJpaRepository;

    public SessionListQueryAdapter(
            SessionJpaRepository sessionJpaRepository,
            SessionMemberJpaRepository sessionMemberJpaRepository) {
        this.sessionJpaRepository = sessionJpaRepository;
        this.sessionMemberJpaRepository = sessionMemberJpaRepository;
    }

    @Override
    public List<SessionSummaryResult> findByUserId(long userId) {
        List<SessionSummaryResult> results = new ArrayList<>();

        for (SessionJpaEntity owned : sessionJpaRepository.findByInstructorId(userId)) {
            results.add(toSummary(owned, MemberRole.INSTRUCTOR));
        }

        List<SessionMemberJpaEntity> memberships = sessionMemberJpaRepository.findByUserId(userId);
        Map<Long, SessionJpaEntity> joinedSessionsById = sessionJpaRepository
                .findAllById(memberships.stream().map(SessionMemberJpaEntity::getSessionId).toList())
                .stream()
                .collect(Collectors.toMap(SessionJpaEntity::getId, Function.identity()));
        for (SessionMemberJpaEntity membership : memberships) {
            SessionJpaEntity session = joinedSessionsById.get(membership.getSessionId());
            if (session != null) {
                results.add(toSummary(session, membership.getRole()));
            }
        }

        return results;
    }

    private SessionSummaryResult toSummary(SessionJpaEntity session, MemberRole role) {
        return new SessionSummaryResult(
                session.getId(), session.getInviteCode(), session.getStatus(), role);
    }
}
