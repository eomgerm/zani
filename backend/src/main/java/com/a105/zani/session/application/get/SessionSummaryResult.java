package com.a105.zani.session.application.get;

import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.model.SessionStatus;

public record SessionSummaryResult(
        Long sessionId, String inviteCode, SessionStatus status, SessionParticipantRole role) {}
