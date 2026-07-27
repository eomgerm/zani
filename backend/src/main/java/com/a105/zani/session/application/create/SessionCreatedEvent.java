package com.a105.zani.session.application.create;

/**
 * 세션(수업)이 생성됐을 때 발행되는 애플리케이션 이벤트. 다른 도메인(예: coach)이 수업 시작 시점을 느슨하게 구독하기 위한 것으로, session 도메인은 구독자를 알지 못한다.
 * (ddd-development-guide §12)
 */
public record SessionCreatedEvent(long sessionId, long instructorId) {}
