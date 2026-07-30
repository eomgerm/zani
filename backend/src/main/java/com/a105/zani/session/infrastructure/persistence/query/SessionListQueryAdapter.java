package com.a105.zani.session.infrastructure.persistence.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.a105.zani.session.application.get.GetSessionListQueryPort;
import com.a105.zani.session.application.get.SessionSummaryResult;
import com.a105.zani.session.domain.model.SessionParticipantRole;
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
            results.add(toSummary(owned, SessionParticipantRole.INSTRUCTOR));
        }

        List<SessionParticipantJpaEntity> participants = sessionParticipantJpaRepository.findByMemberId(userId);
        Map<Long, SessionJpaEntity> joinedSessionsById =
                sessionJpaRepository
                        .findAllById(participants.stream()
                                .map(SessionParticipantJpaEntity::getSessionId)
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(SessionJpaEntity::getId, Function.identity()));
        // 강사는 자기 수업의 참가자이기도 하므로 주최 목록과 참가 목록에 같은 세션이 함께 잡힌다.
        // 그대로 두면 내 수업 목록에 같은 수업이 두 번 뜬다. 주최 항목을 우선해 걸러낸다.
        Set<Long> alreadyListed =
                results.stream().map(SessionSummaryResult::sessionId).collect(Collectors.toSet());
        for (SessionParticipantJpaEntity participant : participants) {
            SessionJpaEntity session = joinedSessionsById.get(participant.getSessionId());
            if (session != null && alreadyListed.add(session.getId())) {
                results.add(toSummary(session, participant.getRole()));
            }
        }

        return results;
    }

    private SessionSummaryResult toSummary(SessionJpaEntity session, SessionParticipantRole role) {
        return new SessionSummaryResult(session.getId(), session.getInviteCode(), session.getStatus(), role);
    }
}
