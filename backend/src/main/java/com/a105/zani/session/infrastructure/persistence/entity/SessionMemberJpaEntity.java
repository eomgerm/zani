package com.a105.zani.session.infrastructure.persistence.entity;

import com.a105.zani.common.infrastructure.persistence.BaseJpaEntity;
import com.a105.zani.session.domain.model.MemberRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
        name = "session_members",
        indexes = {
            @Index(name = "idx_session_members_user_role", columnList = "user_id, role")
        },
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_session_members_session_user", columnNames = {"session_id", "user_id"})
        })
public class SessionMemberJpaEntity extends BaseJpaEntity {

    @Id
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private MemberRole role;

    @Column(name = "first_joined_at", nullable = false)
    private Instant firstJoinedAt;

    @Column(name = "last_accessed_at")
    private Instant lastAccessedAt;

    protected SessionMemberJpaEntity() {
    }

    public SessionMemberJpaEntity(
            Long id,
            Long sessionId,
            Long userId,
            MemberRole role,
            Instant firstJoinedAt,
            Instant lastAccessedAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.userId = userId;
        this.role = role;
        this.firstJoinedAt = firstJoinedAt;
        this.lastAccessedAt = lastAccessedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public MemberRole getRole() {
        return role;
    }

    public Instant getFirstJoinedAt() {
        return firstJoinedAt;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    public void setLastAccessedAt(Instant lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }
}
