package com.a105.zani.attention.application.getattentiontimeline;

import java.time.Instant;

import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantQuery;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantResult;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/** 접근 판정 대역. 판정 자체는 session 도메인 테스트가 본다. 여기서는 역할과 시각만 정해 준다. */
class StubEndedSessionParticipant implements ResolveEndedSessionParticipantUseCase {

    Long participantId = 10L;
    SessionParticipantRole role = SessionParticipantRole.INSTRUCTOR;
    Instant startedAt = Instant.parse("2026-07-28T09:00:00Z");

    /** 과거 세션의 null fallback 과 기록된 종료 시각 우선 경로를 함께 검증한다. */
    Instant endedAt;

    @Override
    public ResolveEndedSessionParticipantResult resolve(ResolveEndedSessionParticipantQuery query) {
        return new ResolveEndedSessionParticipantResult(participantId, role, startedAt, endedAt);
    }
}
