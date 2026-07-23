package com.a105.zani.session.presentation.response;

import com.a105.zani.session.application.getsessionlist.SessionSummaryResult;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.model.SessionStatus;

public record SessionSummaryResponse(
        Long sessionId, String inviteCode, SessionStatus status, SessionParticipantRole role) {

    public static SessionSummaryResponse from(SessionSummaryResult result) {
        return new SessionSummaryResponse(result.sessionId(), result.inviteCode(), result.status(), result.role());
    }
}
