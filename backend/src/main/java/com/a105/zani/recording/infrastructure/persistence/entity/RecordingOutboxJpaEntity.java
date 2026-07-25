package com.a105.zani.recording.infrastructure.persistence.entity;

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

@Entity
@Table(name = "recording_outbox")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RecordingOutboxJpaEntity extends BaseJpaEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "dedup_key", nullable = false, length = 200, unique = true)
    private String dedupKey;

    @Column(name = "outbox_type", nullable = false, length = 40)
    private String outboxType;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "payload", nullable = false, length = 2000)
    private String payload;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "next_attempt_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    public void complete() {
        this.status = STATUS_COMPLETED;
    }

    /** 실패한 claim을 백오프 이후 재시도하도록 PENDING으로 되돌린다. */
    public void scheduleRetry(String error, Instant nextAttemptAt) {
        this.status = STATUS_PENDING;
        this.lastError = error;
        this.nextAttemptAt = nextAttemptAt;
    }

    public void fail(String error) {
        this.status = STATUS_FAILED;
        this.lastError = error;
    }
}
