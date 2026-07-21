package com.a105.zani.session.domain.model;

import java.time.Instant;

public class SessionMember {

    private final Long id;
    private final Long sessionId;
    private final Long userId;
    private final MemberRole role;
    private final Instant firstJoinedAt;
    private Instant lastAccessedAt;

    private SessionMember(
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

    public static SessionMember join(
            Long id, Long sessionId, Long userId, MemberRole role, Instant joinedAt) {
        return new SessionMember(id, sessionId, userId, role, joinedAt, joinedAt);
    }

    public static SessionMember reconstitute(
            Long id,
            Long sessionId,
            Long userId,
            MemberRole role,
            Instant firstJoinedAt,
            Instant lastAccessedAt) {
        return new SessionMember(id, sessionId, userId, role, firstJoinedAt, lastAccessedAt);
    }

    public void recordAccess(Instant accessedAt) {
        this.lastAccessedAt = accessedAt;
    }

    public Long id() {
        return id;
    }

    public Long sessionId() {
        return sessionId;
    }

    public Long userId() {
        return userId;
    }

    public MemberRole role() {
        return role;
    }

    public Instant firstJoinedAt() {
        return firstJoinedAt;
    }

    public Instant lastAccessedAt() {
        return lastAccessedAt;
    }
}
