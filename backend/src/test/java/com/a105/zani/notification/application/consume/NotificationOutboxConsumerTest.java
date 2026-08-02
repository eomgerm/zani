package com.a105.zani.notification.application.consume;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.a105.zani.notification.application.InMemoryNotificationOutboxPort;
import com.a105.zani.notification.application.port.EmailMessage;
import com.a105.zani.notification.application.port.EmailSenderPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationOutboxConsumerTest {

    private static final Instant NOW = Instant.parse("2026-08-02T09:00:00Z");
    private static final long SESSION_ID = 500L;
    private static final long MEMBER_ID = 1L;

    private final InMemoryNotificationOutboxPort outbox = new InMemoryNotificationOutboxPort();
    private final CapturingEmailSender sender = new CapturingEmailSender();
    private final ReportReadyEmailComposer composer = new ReportReadyEmailComposer("https://app.test");

    private NotificationOutboxConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new NotificationOutboxConsumer(outbox, sender, composer, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void sendsPendingAndMarksItSentWithALoginLink() {
        outbox.seed(SESSION_ID, MEMBER_ID, "a@zani.app", "학생A", 0, NOW);

        consumer.consume();

        assertEquals(1, sender.sent.size());
        EmailMessage message = sender.sent.get(0);
        assertEquals("a@zani.app", message.to());
        // 이메일은 세션 리포트로 가는 로그인 링크를 담아야 한다.
        assertTrue(message.bodyHtml().contains("https://app.test/sessions/" + SESSION_ID + "/report"));
        assertEquals("SENT", outbox.rows.get(0).status);
        assertNotNull(outbox.rows.get(0).sentAt);
    }

    @Test
    void recordsFailureAndSchedulesRetry() {
        InMemoryNotificationOutboxPort.Row row = outbox.seed(SESSION_ID, MEMBER_ID, "a@zani.app", "학생A", 0, NOW);
        sender.failWith = new RuntimeException("smtp down");

        consumer.consume();

        assertTrue(sender.sent.isEmpty());
        // 실패는 사유로 기록되고 재시도를 위해 PENDING 으로 되돌아가며, 백오프로 다음 시도 시각이 미뤄진다.
        assertEquals("PENDING", row.status);
        assertEquals("smtp down", row.lastError);
        assertTrue(row.nextAttemptAt.isAfter(NOW));
    }

    @Test
    void givesUpAfterTheRetryCeiling() {
        // 이미 재시도 상한(10)까지 온 행. claim 이 11로 올리기 전 값(10)으로 상한을 판정한다.
        InMemoryNotificationOutboxPort.Row row = outbox.seed(SESSION_ID, MEMBER_ID, "a@zani.app", "학생A", 10, NOW);
        sender.failWith = new RuntimeException("smtp down");

        consumer.consume();

        assertEquals("FAILED", row.status);
        assertEquals("smtp down", row.lastError);
    }

    @Test
    void doesNotResendAnAlreadySentNotification() {
        InMemoryNotificationOutboxPort.Row row = outbox.seed(SESSION_ID, MEMBER_ID, "a@zani.app", "학생A", 1, NOW);
        row.status = "SENT";

        consumer.consume();

        assertTrue(sender.sent.isEmpty());
    }

    private static final class CapturingEmailSender implements EmailSenderPort {
        private final List<EmailMessage> sent = new ArrayList<>();
        private RuntimeException failWith;

        @Override
        public void send(EmailMessage message) {
            if (failWith != null) {
                throw failWith;
            }
            sent.add(message);
        }
    }
}
