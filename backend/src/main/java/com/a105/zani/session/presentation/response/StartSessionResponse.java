package com.a105.zani.session.presentation.response;

import java.time.Instant;

import com.a105.zani.session.application.start.StartSessionResult;
import com.a105.zani.session.domain.model.SessionStatus;

public record StartSessionResponse(
        Long sessionId, SessionStatus status, String inviteCode, Instant expiresAt, boolean started) {

    public static StartSessionResponse from(StartSessionResult result) {
        return new StartSessionResponse(
                result.sessionId(), result.status(), result.inviteCode(), result.expiresAt(), result.started());
    }
}
