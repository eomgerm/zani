package com.a105.zani.session.presentation.response;

import com.a105.zani.session.application.createsession.CreateSessionResult;
import com.a105.zani.session.domain.model.SessionStatus;
import java.time.Instant;

public record CreateSessionResponse(
        Long sessionId,
        String inviteCode,
        SessionStatus status,
        String role,
        Instant expiresAt) {

    private static final String INSTRUCTOR_ROLE = "INSTRUCTOR";

    public static CreateSessionResponse from(CreateSessionResult result) {
        return new CreateSessionResponse(
                result.sessionId(),
                result.inviteCode(),
                result.status(),
                INSTRUCTOR_ROLE,
                result.expiresAt());
    }
}
