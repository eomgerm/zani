package com.a105.zani.session.application.presence;

import java.time.Instant;

import com.a105.zani.session.domain.model.ConnectionState;

/** 세션 참가자의 presence heartbeat 입력. userId는 인증 주체에서, sessionId는 경로에서 온다. */
public record RecordPresenceCommand(
        Long sessionId, Long userId, Instant heartbeatAt, ConnectionState connectionState) {}
