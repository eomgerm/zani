package com.a105.zani.session.domain.model;

import java.time.Instant;

/**
 * 세션 참가자.
 *
 * <p>시각이 세 종류인 게 핵심이다. {@code firstJoinedAt} 은 입장 API 를 부른 시각, {@code mediaFirstJoinedAt} 은 실제로 LiveKit 방에 들어온 시각,
 * {@code lastAccessedAt} 은 가장 최근에 세션을 만진 시각이다. 프리조인 화면만 보고 나간 학생은 앞의 것만 갖는다 — 출석은 뒤의 것으로 판단해야 한다.
 */
public class SessionParticipant {

    private final Long id;
    private final Long sessionId;
    private final Long userId;
    private final SessionParticipantRole role;
    private final Instant firstJoinedAt;
    private Instant lastAccessedAt;
    private Instant mediaFirstJoinedAt;
    private Instant mediaLastLeftAt;

    private SessionParticipant(
            Long id,
            Long sessionId,
            Long userId,
            SessionParticipantRole role,
            Instant firstJoinedAt,
            Instant lastAccessedAt,
            Instant mediaFirstJoinedAt,
            Instant mediaLastLeftAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.userId = userId;
        this.role = role;
        this.firstJoinedAt = firstJoinedAt;
        this.lastAccessedAt = lastAccessedAt;
        this.mediaFirstJoinedAt = mediaFirstJoinedAt;
        this.mediaLastLeftAt = mediaLastLeftAt;
    }

    public static SessionParticipant join(
            Long id, Long sessionId, Long userId, SessionParticipantRole role, Instant joinedAt) {
        return new SessionParticipant(id, sessionId, userId, role, joinedAt, joinedAt, null, null);
    }

    public static SessionParticipant reconstitute(
            Long id,
            Long sessionId,
            Long userId,
            SessionParticipantRole role,
            Instant firstJoinedAt,
            Instant lastAccessedAt,
            Instant mediaFirstJoinedAt,
            Instant mediaLastLeftAt) {
        return new SessionParticipant(
                id, sessionId, userId, role, firstJoinedAt, lastAccessedAt, mediaFirstJoinedAt, mediaLastLeftAt);
    }

    public void recordAccess(Instant accessedAt) {
        this.lastAccessedAt = accessedAt;
    }

    /**
     * 실제로 LiveKit 방에 들어왔음을 기록한다.
     *
     * <p>최초 입장 시각은 <b>한 번만</b> 심는다. 재접속마다 갱신하면 네트워크가 한 번 끊긴 학생의 출석이 그 시점부터 시작된 것으로 남는다.
     *
     * @return 이번 호출이 최초 입장으로 기록됐으면 true
     */
    public boolean recordMediaJoin(Instant joinedAt) {
        if (mediaFirstJoinedAt != null) {
            return false;
        }
        this.mediaFirstJoinedAt = joinedAt;
        return true;
    }

    /** LiveKit 방에서 나갔음을 기록한다. 재접속해도 이 값을 지우지 않고 다음 이탈에 덮어쓴다 — 마지막으로 나간 시각이 곧 출석의 끝이다. */
    public void recordMediaLeave(Instant leftAt) {
        this.mediaLastLeftAt = leftAt;
    }

    /** 미디어 서버에서 이 참가자를 가리키는 identity. */
    public String mediaIdentity() {
        return ParticipantIdentity.of(id);
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

    /** 실제로 LiveKit 방에 처음 들어온 시각. 입장 API 만 부르고 방에 들어오지 않았으면 null 이다. */
    public Instant mediaFirstJoinedAt() {
        return mediaFirstJoinedAt;
    }

    public Instant mediaLastLeftAt() {
        return mediaLastLeftAt;
    }
}
