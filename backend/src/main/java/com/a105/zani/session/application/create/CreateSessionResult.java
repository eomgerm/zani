package com.a105.zani.session.application.create;

import java.time.Instant;

import com.a105.zani.session.domain.model.SessionStatus;

public record CreateSessionResult(Long sessionId, String inviteCode, SessionStatus status, Instant expiresAt) {}
