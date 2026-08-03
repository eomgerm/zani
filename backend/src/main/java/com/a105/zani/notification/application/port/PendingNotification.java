package com.a105.zani.notification.application.port;

/** 아직 보내지 못한 알림 한 건. {@code attemptCount} 는 claim 전 값이라 재시도 상한 판정에 쓴다. */
public record PendingNotification(
        Long id, Long sessionId, Long memberId, String email, String displayName, int attemptCount) {}
