package com.a105.zani.session.infrastructure.persistence.entity;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import com.a105.zani.common.persistence.BaseJpaEntity;
import com.a105.zani.session.domain.model.SessionStatus;

@Entity
@Table(
        name = "sessions",
        indexes = {@Index(name = "idx_sessions_instructor_status", columnList = "instructor_id, status")},
        uniqueConstraints = {@UniqueConstraint(name = "uq_sessions_invite_code", columnNames = "invite_code")})
public class SessionJpaEntity extends BaseJpaEntity {

    @Id
    private Long id;

    @Column(name = "instructor_id", nullable = false)
    private Long instructorId;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "invite_code", nullable = false, length = 8)
    private String inviteCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private SessionStatus status;

    @Column(name = "limited_mode", nullable = false)
    private boolean limitedMode;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "note_due_at")
    private Instant noteDueAt;

    protected SessionJpaEntity() {}

    public SessionJpaEntity(
            Long id,
            Long instructorId,
            String title,
            String inviteCode,
            SessionStatus status,
            boolean limitedMode,
            Instant startedAt) {
        this.id = id;
        this.instructorId = instructorId;
        this.title = title;
        this.inviteCode = inviteCode;
        this.status = status;
        this.limitedMode = limitedMode;
        this.startedAt = startedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getInstructorId() {
        return instructorId;
    }

    public String getTitle() {
        return title;
    }

    public String getInviteCode() {
        return inviteCode;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public boolean isLimitedMode() {
        return limitedMode;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public Instant getNoteDueAt() {
        return noteDueAt;
    }
}
