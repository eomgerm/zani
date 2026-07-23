package com.a105.zani.session.infrastructure.persistence.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.a105.zani.session.application.getsessionlist.GetSessionListQueryPort;
import com.a105.zani.session.application.getsessionlist.SessionSummaryResult;
import com.a105.zani.session.domain.model.MemberRole;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.infrastructure.persistence.entity.SessionJpaEntity;
import com.a105.zani.session.infrastructure.persistence.entity.SessionParticipantJpaEntity;
import com.a105.zani.session.infrastructure.persistence.repository.SessionJpaRepository;
import com.a105.zani.session.infrastructure.persistence.repository.SessionParticipantJpaRepository;

@Component
public class SessionListQueryAdapter implements GetSessionListQueryPort {

    private final SessionJpaRepository sessionJpaRepository;
    private final SessionParticipantJpaRepository sessionParticipantJpaRepository;

    public SessionListQueryAdapter(
            SessionJpaRepository sessionJpaRepository,
            SessionParticipantJpaRepository sessionParticipantJpaRepository) {
        this.sessionJpaRepository = sessionJpaRepository;
        this.sessionParticipantJpaRepository = sessionParticipantJpaRepository;
    }

    @Override
    public List<SessionSummaryResult> findByUserId(long userId) {
        List<SessionSummaryResult> results = new ArrayList<>();

        for (SessionJpaEntity owned : sessionJpaRepository.findByHostMemberId(userId)) {
            results.add(toSummary(owned, MemberRole.INSTRUCTOR));
        }

        List<SessionParticipantJpaEntity> memberships = sessionParticipantJpaRepository.findByMemberId(userId);
        Map<Long, SessionJpaEntity> joinedSessionsById =
                sessionJpaRepository
                        .findAllById(memberships.stream()
                                .map(SessionParticipantJpaEntity::getSessionId)
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(SessionJpaEntity::getId, Function.identity()));
        for (SessionParticipantJpaEntity membership : memberships) {
            SessionJpaEntity session = joinedSessionsById.get(membership.getSessionId());
            if (session != null) {
                results.add(toSummary(session, MemberRole.valueOf(membership.getRole())));
            }
        }

        return results;
    }

    private SessionSummaryResult toSummary(SessionJpaEntity session, MemberRole role) {
        return new SessionSummaryResult(
                session.getId(), session.getInviteCode(), SessionStatus.valueOf(session.getStatus()), role);
    }
}
