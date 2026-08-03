package com.a105.zani.notification.application.port;

/** 리포트 알림을 받을 학생 한 명(발견 시점 스냅샷). */
public record ReportRecipient(Long memberId, String email, String displayName) {}
