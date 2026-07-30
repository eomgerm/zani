package com.a105.zani.session.application.start;

import java.time.Instant;

import com.a105.zani.session.domain.model.SessionStatus;

/** @param started 이번 요청으로 시작됐으면 true, 이미 진행 중이었으면 false. 두 번 눌러도 시작 시각이 밀리지 않았음을 호출자가 확인할 수 있다. */
public record StartSessionResult(
        Long sessionId, String inviteCode, SessionStatus status, Instant expiresAt, boolean started) {}
