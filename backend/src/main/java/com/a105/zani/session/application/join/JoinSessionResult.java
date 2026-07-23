package com.a105.zani.session.application.join;

import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.model.SessionStatus;

public record JoinSessionResult(Long sessionId, String inviteCode, SessionStatus status, SessionParticipantRole role) {}
