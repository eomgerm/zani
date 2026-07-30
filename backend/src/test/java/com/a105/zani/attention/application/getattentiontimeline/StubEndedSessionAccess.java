package com.a105.zani.attention.application.getattentiontimeline;

import java.time.Instant;

import com.a105.zani.session.application.resolveendedsessionaccess.ResolveEndedSessionAccessQuery;
import com.a105.zani.session.application.resolveendedsessionaccess.ResolveEndedSessionAccessResult;
import com.a105.zani.session.application.resolveendedsessionaccess.ResolveEndedSessionAccessUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/** 접근 판정 대역. 판정 자체는 session 도메인 테스트가 본다. 여기서는 역할과 시각만 정해 준다. */
class StubEndedSessionAccess implements ResolveEndedSessionAccessUseCase {

    Long participantId = 10L;
    SessionParticipantRole role = SessionParticipantRole.INSTRUCTOR;
    Instant startedAt = Instant.parse("2026-07-28T09:00:00Z");

    /** 현재 운영 데이터에서는 늘 비어 있다. 값이 생겼을 때 우선 쓰이는지를 보려고 열어 둔다. */
    Instant endedAt;

    @Override
    public ResolveEndedSessionAccessResult resolve(ResolveEndedSessionAccessQuery query) {
        return new ResolveEndedSessionAccessResult(participantId, role, startedAt, endedAt);
    }
}
