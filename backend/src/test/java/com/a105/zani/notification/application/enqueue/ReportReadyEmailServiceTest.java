package com.a105.zani.notification.application.enqueue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.a105.zani.notification.application.InMemoryNotificationOutboxPort;
import com.a105.zani.notification.application.port.ReadyReportQueryPort;
import com.a105.zani.notification.application.port.ReportRecipient;
import com.a105.zani.notification.application.port.ReportRecipientQueryPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportReadyEmailServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-02T09:00:00Z");
    private static final long SESSION_ID = 500L;

    private final InMemoryNotificationOutboxPort outbox = new InMemoryNotificationOutboxPort();
    private final StubReadyReports readyReports = new StubReadyReports();
    private final StubRecipients recipients = new StubRecipients();

    private ReportReadyEmailService service;

    @BeforeEach
    void setUp() {
        service = new ReportReadyEmailService(readyReports, recipients, outbox, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void enqueuesOneNotificationPerStudentOfAReadySession() {
        readyReports.sessionIds = List.of(SESSION_ID);
        recipients.bySession = Map.of(
                SESSION_ID,
                List.of(new ReportRecipient(1L, "a@zani.app", "학생A"), new ReportRecipient(2L, "b@zani.app", "학생B")));

        service.enqueueReadyReports();

        assertEquals(2, outbox.rows.size());
        assertTrue(outbox.rows.stream().allMatch(row -> row.sessionId == SESSION_ID));
    }

    @Test
    void isIdempotentAcrossRepeatedPolls() {
        readyReports.sessionIds = List.of(SESSION_ID);
        recipients.bySession = Map.of(SESSION_ID, List.of(new ReportRecipient(1L, "a@zani.app", "학생A")));

        // 같은 세션이 다음 폴링에서 다시 걸려도(발견 조건이 아직 반영 전) 학생당 알림은 한 건이어야 한다.
        service.enqueueReadyReports();
        service.enqueueReadyReports();

        assertEquals(1, outbox.rows.size());
    }

    @Test
    void createsNoRowsWhenTheSessionHasNoStudents() {
        readyReports.sessionIds = List.of(SESSION_ID);
        recipients.bySession = Map.of(SESSION_ID, List.of());

        service.enqueueReadyReports();

        assertTrue(outbox.rows.isEmpty());
    }

    private static final class StubReadyReports implements ReadyReportQueryPort {
        private List<Long> sessionIds = List.of();

        @Override
        public List<Long> findReadyReportSessionsWithoutNotification(int limit) {
            return sessionIds;
        }
    }

    private static final class StubRecipients implements ReportRecipientQueryPort {
        private Map<Long, List<ReportRecipient>> bySession = Map.of();

        @Override
        public List<ReportRecipient> findRecipients(Long sessionId) {
            return bySession.getOrDefault(sessionId, List.of());
        }
    }
}
