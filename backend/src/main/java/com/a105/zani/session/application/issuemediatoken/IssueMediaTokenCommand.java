package com.a105.zani.session.application.issuemediatoken;

/** 미디어 토큰 발급 요청. sessionId는 경로에서, userId는 인증 주체에서 온다(요청 body 아님). */
public record IssueMediaTokenCommand(Long sessionId, Long userId) {}
