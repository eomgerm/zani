package com.a105.zani.session.presentation.response;

import com.a105.zani.session.application.joinsession.JoinSessionResult;
import com.a105.zani.session.domain.model.MemberRole;
import com.a105.zani.session.domain.model.SessionStatus;

public record JoinSessionResponse(Long sessionId, String inviteCode, SessionStatus status, MemberRole role) {

    public static JoinSessionResponse from(JoinSessionResult result) {
        return new JoinSessionResponse(result.sessionId(), result.inviteCode(), result.status(), result.role());
    }
}
