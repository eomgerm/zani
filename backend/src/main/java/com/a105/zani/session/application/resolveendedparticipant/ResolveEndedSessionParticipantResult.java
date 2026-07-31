package com.a105.zani.session.application.resolveendedparticipant;

import com.a105.zani.session.domain.model.SessionParticipantRole;

/** 확인된 종료 세션의 참가자. 다른 도메인은 session 엔티티가 아니라 이 값만 받는다. */
public record ResolveEndedSessionParticipantResult(Long participantId, SessionParticipantRole role) {}
