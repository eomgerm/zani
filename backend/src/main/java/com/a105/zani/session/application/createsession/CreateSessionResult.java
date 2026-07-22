package com.a105.zani.session.application.createsession;

import com.a105.zani.session.domain.model.SessionStatus;
import java.time.Instant;

public record CreateSessionResult(
        Long sessionId,
        String inviteCode,
        SessionStatus status,
        Instant expiresAt) {
}
