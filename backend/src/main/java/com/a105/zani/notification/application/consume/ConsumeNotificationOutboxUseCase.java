package com.a105.zani.notification.application.consume;

/** 알림 outbox 의 PENDING 행을 소비해 이메일을 보낸다. */
public interface ConsumeNotificationOutboxUseCase {

    void consume();
}
