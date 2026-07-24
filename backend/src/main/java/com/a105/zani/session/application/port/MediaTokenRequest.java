package com.a105.zani.session.application.port;

import com.a105.zani.session.domain.model.SessionParticipantRole;

/** LiveKit 토큰 발급에 필요한 서버 결정 값. 요청 body가 아니라 서버가 DB에서 재구성한다. roomName은 환경 설정에 의존하므로 어댑터가 sessionId로 생성한다. */
public record MediaTokenRequest(String identity, String displayName, SessionParticipantRole role, Long sessionId) {}
