package com.a105.zani.notification.infrastructure.mail;

/** SMTP 전송이 실패했다(설정 누락 포함). consumer 가 실패 기록·재시도로 처리하도록 던진다. */
public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(String message) {
        super(message);
    }

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
