package com.a105.zani.notification.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.a105.zani.notification.application.port.NewNotification;
import com.a105.zani.notification.application.port.NotificationOutboxPort;
import com.a105.zani.notification.application.port.PendingNotification;

/** 알림 outbox 의 in-memory 대역. dedup_key UNIQUE·claim 선점 등 실제 계약을 그대로 지킨다. */
public class InMemoryNotificationOutboxPort implements NotificationOutboxPort {

    public static final class Row {
        public final long id;
        public final long sessionId;
        public final long memberId;
        public final String email;
        public final String displayName;
        public final String dedupKey;
        public String status;
        public int attemptCount;
        public Instant nextAttemptAt;
        public String lastError;
        public Instant sentAt;

        private Row(
                long id,
                long sessionId,
                long memberId,
                String email,
                String displayName,
                String dedupKey,
                String status,
                int attemptCount,
                Instant nextAttemptAt) {
            this.id = id;
            this.sessionId = sessionId;
            this.memberId = memberId;
            this.email = email;
            this.displayName = displayName;
            this.dedupKey = dedupKey;
            this.status = status;
            this.attemptCount = attemptCount;
            this.nextAttemptAt = nextAttemptAt;
        }
    }

    public final List<Row> rows = new ArrayList<>();
    private long sequence = 1;

    @Override
    public int enqueueAll(List<NewNotification> notifications, Instant now) {
        int inserted = 0;
        for (NewNotification notification : notifications) {
            if (rows.stream().anyMatch(row -> row.dedupKey.equals(notification.dedupKey()))) {
                continue; // dedup_key UNIQUE 충돌: 멱등하게 무시.
            }
            rows.add(new Row(
                    sequence++,
                    notification.sessionId(),
                    notification.memberId(),
                    notification.email(),
                    notification.displayName(),
                    notification.dedupKey(),
                    "PENDING",
                    0,
                    now));
            inserted++;
        }
        return inserted;
    }

    /** 테스트용: 특정 시도 횟수·상태의 행을 미리 심는다. */
    public Row seed(long sessionId, long memberId, String email, String displayName, int attemptCount, Instant next) {
        Row row = new Row(
                sequence++,
                sessionId,
                memberId,
                email,
                displayName,
                sessionId + ":" + memberId + ":REPORT_READY",
                "PENDING",
                attemptCount,
                next);
        rows.add(row);
        return row;
    }

    @Override
    public List<PendingNotification> fetchDue(int limit, Instant now) {
        return rows.stream()
                .filter(row -> row.status.equals("PENDING") && !row.nextAttemptAt.isAfter(now))
                .limit(limit)
                .map(row -> new PendingNotification(
                        row.id, row.sessionId, row.memberId, row.email, row.displayName, row.attemptCount))
                .toList();
    }

    @Override
    public boolean claim(Long id, Instant now) {
        Row row = find(id);
        if (row == null || !row.status.equals("PENDING")) {
            return false;
        }
        row.status = "IN_PROGRESS";
        row.attemptCount += 1;
        return true;
    }

    @Override
    public void requeueExpiredClaims(Instant cutoff, Instant now) {
        for (Row row : rows) {
            if (row.status.equals("IN_PROGRESS")) {
                row.status = "PENDING";
            }
        }
    }

    @Override
    public void markSent(Long id, Instant now) {
        Row row = find(id);
        if (row != null) {
            row.status = "SENT";
            row.sentAt = now;
        }
    }

    @Override
    public void markRetry(Long id, String error, Instant nextAttemptAt) {
        Row row = find(id);
        if (row != null) {
            row.status = "PENDING";
            row.lastError = error;
            row.nextAttemptAt = nextAttemptAt;
        }
    }

    @Override
    public void markFailed(Long id, String error) {
        Row row = find(id);
        if (row != null) {
            row.status = "FAILED";
            row.lastError = error;
        }
    }

    private Row find(Long id) {
        return rows.stream().filter(row -> row.id == id).findFirst().orElse(null);
    }
}
