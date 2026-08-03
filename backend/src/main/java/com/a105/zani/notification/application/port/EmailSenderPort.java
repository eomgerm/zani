package com.a105.zani.notification.application.port;

/** 이메일 한 통을 실제 전송한다. SMTP 어댑터가 구현한다. 발송 실패는 예외로 던져 consumer 가 실패 기록·재시도로 처리한다. */
public interface EmailSenderPort {

    void send(EmailMessage message);
}
