package com.a105.zani.session.application.issuemediatoken;

import java.time.Instant;

/**
 * @param expiresAt LiveKit 토큰 만료 시각(TTL 10분)
 * @param sessionExpiresAt 최대 수업 시간(3시간)에 도달해 세션이 자동 종료될 시각. 강의실이 종료 임박 안내를 띄우는 기준이다.
 */
public record IssueMediaTokenResult(
        String liveKitUrl,
        String accessToken,
        String roomName,
        String participantIdentity,
        Instant expiresAt,
        Instant sessionExpiresAt) {}
