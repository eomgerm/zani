package com.a105.zani.session.application.resolveparticipant;

/** 진행 중인 세션에서 참가자를 찾기 위한 입력. userId는 인증 주체에서, sessionId는 경로에서 온다. */
public record ResolveSessionParticipantQuery(Long sessionId, Long userId) {}
