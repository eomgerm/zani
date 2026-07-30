package com.a105.zani.session.application.resolveendedsessionaccess;

/** 종료된 세션의 리포트를 열려는 요청. memberId 는 인증 주체에서, sessionId 는 경로에서 온다. */
public record ResolveEndedSessionAccessQuery(Long sessionId, Long memberId) {}
