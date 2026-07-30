package com.a105.zani.session.presentation.response;

import java.time.Instant;

import com.a105.zani.session.application.start.StartSessionResult;
import com.a105.zani.session.domain.model.SessionStatus;

/**
 * @param sessionId TSID 는 JS 안전 정수 범위를 넘어 브라우저에서 반올림되므로 문자열로 내보낸다
 * @param started 이번 요청으로 시작됐으면 true, 이미 진행 중이었으면 false
 */
public record StartSessionResponse(
        String sessionId, String inviteCode, SessionStatus status, Instant expiresAt, boolean started) {

    public static StartSessionResponse from(StartSessionResult result) {
        return new StartSessionResponse(
                String.valueOf(result.sessionId()),
                result.inviteCode(),
                result.status(),
                result.expiresAt(),
                result.started());
    }
}
