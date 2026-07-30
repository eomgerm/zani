package com.a105.zani.session.application.issuemediatoken;

import java.time.Instant;

import com.a105.zani.session.domain.model.SessionStatus;

/**
 * @param expiresAt LiveKit 토큰 만료 시각(TTL 10분)
 * @param sessionExpiresAt 최대 수업 시간(3시간)에 도달해 세션이 자동 종료될 시각. 강의실이 종료 임박 안내를 띄우는 기준이다.
 * @param sessionTitle 강사가 입력한 강의명. 강의실은 진입 시 이 응답만 받으므로 제목도 여기서 함께 내린다.
 * @param sessionStatus 이 세션의 현재 상태. 강사 화면이 PREPARING 을 보고 연결 성공 뒤 시작을 호출한다 — 연결되지 않은 채로 초대 코드가 열리지 않게 하려는 것이다.
 */
public record IssueMediaTokenResult(
        String liveKitUrl,
        String accessToken,
        String roomName,
        String participantIdentity,
        Instant expiresAt,
        Instant sessionExpiresAt,
        String sessionTitle,
        SessionStatus sessionStatus) {}
