package com.a105.zani.notification.infrastructure.persistence.entity;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.a105.zani.common.infrastructure.persistence.BaseJpaEntity;

/**
 * notification_outbox 행. 한 수신자에게 보낼 알림 한 건이다.
 *
 * <p>상태 문자열은 여기의 STATUS_* 상수만을 단일 소스로 쓰고 쿼리·서비스에 리터럴을 두지 않는다. dedup_key(세션·수신자·유형)로 같은 알림이 두 번 쌓이지 않는다.
 */
@Entity
@Table(name = "notification_outbox")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class NotificationOutboxJpaEntity extends BaseJpaEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "type", nullable = false, length = 40)
    private String type;

    @Column(name = "dedup_key", nullable = false, length = 200, unique = true)
    private String dedupKey;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "sent_at", columnDefinition = "DATETIME(6)")
    private Instant sentAt;

    public void markSent(Instant sentAt) {
        this.status = STATUS_SENT;
        this.sentAt = sentAt;
    }

    public void scheduleRetry(String error, Instant nextAttemptAt) {
        this.status = STATUS_PENDING;
        this.lastError = error;
        this.nextAttemptAt = nextAttemptAt;
    }

    public void markFailed(String error) {
        this.status = STATUS_FAILED;
        this.lastError = error;
    }
}
