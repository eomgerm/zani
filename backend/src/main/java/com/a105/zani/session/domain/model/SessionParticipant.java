package com.a105.zani.session.domain.model;

import java.time.Instant;

public class SessionParticipant {

    private final Long id;
    private final Long sessionId;
    private final Long userId;
    private final SessionParticipantRole role;
    private Instant firstJoinedAt;
    private Instant lastAccessedAt;

    private SessionParticipant(
            Long id,
            Long sessionId,
            Long userId,
            SessionParticipantRole role,
            Instant firstJoinedAt,
            Instant lastAccessedAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.userId = userId;
        this.role = role;
        this.firstJoinedAt = firstJoinedAt;
        this.lastAccessedAt = lastAccessedAt;
    }

    /**
     * 참가 관계만 만든다. 사후 자료 접근 자격({@code firstJoinedAt})은 비워 둔다 — 입장 API 호출은 실제 미디어 연결의 근거가 아니다(FRD ACCESS-002). 자격은
     * LiveKit 참가자 연결 통지를 받아 {@link #confirmConnection(Instant)}이 채운다.
     */
    public static SessionParticipant join(
            Long id, Long sessionId, Long userId, SessionParticipantRole role, Instant accessedAt) {
        return new SessionParticipant(id, sessionId, userId, role, null, accessedAt);
    }

    public static SessionParticipant reconstitute(
            Long id,
            Long sessionId,
            Long userId,
            SessionParticipantRole role,
            Instant firstJoinedAt,
            Instant lastAccessedAt) {
        return new SessionParticipant(id, sessionId, userId, role, firstJoinedAt, lastAccessedAt);
    }

    public void recordAccess(Instant accessedAt) {
        this.lastAccessedAt = accessedAt;
    }

    /**
     * 실제 미디어 연결이 확인된 최초 시각을 기록한다. 이 시각이 곧 사후 자료 접근 자격이다.
     *
     * <p>최초 1회만 채운다. 중복 통지와 재접속은 자격을 바꾸지 않는다 — 재접속마다 시각이 밀리면 "언제부터 수업에 있었나"가 사라지고, 중간에 나갔다 온 사람이 처음부터 있던 사람보다 늦게 들어온
     * 것으로 보인다. 이탈은 자격을 취소하지 않으므로 되돌리는 행동을 두지 않는다.
     *
     * @return 이번 호출이 자격을 새로 부여했으면 {@code true}, 이미 자격이 있어 아무것도 바뀌지 않았으면 {@code false}
     */
    public boolean confirmConnection(Instant connectedAt) {
        if (firstJoinedAt != null) {
            return false;
        }
        this.firstJoinedAt = connectedAt;
        return true;
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

    public SessionParticipantRole role() {
        return role;
    }

    public Instant firstJoinedAt() {
        return firstJoinedAt;
    }

    public Instant lastAccessedAt() {
        return lastAccessedAt;
    }
}
