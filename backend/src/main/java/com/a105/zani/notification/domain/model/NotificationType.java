package com.a105.zani.notification.domain.model;

/** 알림 종류. 지금은 리포트 준비 완료 하나지만, outbox·dedup 키에 유형을 담아 뒤에 종류가 늘어도 같은 파이프라인을 쓴다. */
public enum NotificationType {
    REPORT_READY
}
