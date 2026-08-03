package com.a105.zani.notification.application.port;

/** 새로 등록할 알림. {@code dedupKey} 는 (세션·수신자·유형)을 유일하게 식별해 재실행·중복 발견에서 같은 알림이 두 번 쌓이지 않게 한다. */
public record NewNotification(
        Long sessionId, Long memberId, String email, String displayName, String type, String dedupKey) {}
