package com.a105.zani.session.presentation.response;

import java.time.Instant;

import com.a105.zani.session.application.createsession.CreateSessionResult;
import com.a105.zani.session.domain.model.MemberRole;
import com.a105.zani.session.domain.model.SessionStatus;

public record CreateSessionResponse(
        Long sessionId, String inviteCode, SessionStatus status, MemberRole role, Instant expiresAt) {

    public static CreateSessionResponse from(CreateSessionResult result) {
        return new CreateSessionResponse(
                result.sessionId(), result.inviteCode(), result.status(), MemberRole.INSTRUCTOR, result.expiresAt());
    }
}
