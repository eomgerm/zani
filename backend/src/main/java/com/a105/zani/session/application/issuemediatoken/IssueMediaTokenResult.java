package com.a105.zani.session.application.issuemediatoken;

import java.time.Instant;

/**
 * @param expiresAt LiveKit 토큰 만료 시각(TTL 10분)
 * @param sessionExpiresAt 최대 수업 시간(3시간)에 도달해 세션이 자동 종료될 시각. 강의실이 종료 임박 안내를 띄우는 기준이다.
 * @param sessionTitle 강사가 입력한 강의명. 강의실은 진입 시 이 응답만 받으므로 제목도 여기서 함께 내린다.
 */
public record IssueMediaTokenResult(
        String liveKitUrl,
        String accessToken,
        String roomName,
        String participantIdentity,
        Instant expiresAt,
        Instant sessionExpiresAt,
        String sessionTitle) {}
