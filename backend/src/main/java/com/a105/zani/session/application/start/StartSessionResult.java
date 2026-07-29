package com.a105.zani.session.application.start;

import java.time.Instant;

import com.a105.zani.session.domain.model.SessionStatus;

/**
 * @param inviteCode 이 시점부터 학생 입장에 쓸 수 있는 초대 코드
 * @param expiresAt 최대 수업 시간(3시간)에 도달해 자동 종료될 시각
 * @param started 이번 호출이 실제로 시작시켰으면 true, 이미 진행 중이었으면 false
 */
public record StartSessionResult(
        Long sessionId, SessionStatus status, String inviteCode, Instant expiresAt, boolean started) {}
