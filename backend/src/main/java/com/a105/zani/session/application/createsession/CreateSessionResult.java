package com.a105.zani.session.application.createsession;

import java.time.Instant;

import com.a105.zani.session.domain.model.SessionStatus;

public record CreateSessionResult(Long sessionId, String inviteCode, SessionStatus status, Instant expiresAt) {}
