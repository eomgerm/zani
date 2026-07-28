package com.a105.zani.session.application.resolveparticipant;

import com.a105.zani.session.domain.model.SessionParticipantRole;

/** 확인된 세션 참가자. 다른 도메인은 session 엔티티가 아니라 이 값만 받는다. */
public record ResolveSessionParticipantResult(Long participantId, SessionParticipantRole role) {}
