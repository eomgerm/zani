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
     * 실제로 LiveKit 방에 들어왔음을 기록한다. 최초 입장 시각은 가장 이른 값으로 수렴한다.
     *
     * <p>"처음 받은 값을 고정"이 아니라 min 인 이유는 webhook 의 도착 순서가 보장되지 않기 때문이다. 재전송이나 지연으로 재접속 이벤트가 최초 입장보다 먼저 처리되면, 고정 방식에서는 출석이
     * 실제보다 늦게 시작된 것으로 남는다. 늦게 도착한 더 이른 시각도 받아들여야 값이 사실에 수렴한다.
     *
     * @return 이번 호출로 최초 입장 시각이 바뀌었으면 true
     */
    public boolean recordMediaJoin(Instant joinedAt) {
        if (mediaFirstJoinedAt != null && !joinedAt.isBefore(mediaFirstJoinedAt)) {
            return false;
        }
        this.mediaFirstJoinedAt = joinedAt;
        return true;
    }

    /**
     * LiveKit 방에서 나갔음을 기록한다. 마지막 이탈 시각은 가장 늦은 값으로 수렴한다.
     *
     * <p>재접속해도 이 값을 지우지 않는다 — 마지막으로 나간 시각이 곧 출석의 끝이다. 무조건 덮어쓰지 않고 max 를 쓰는 이유는 입장과 같다: 순서가 뒤바뀐 이벤트가 먼저 처리되면 종료 시각이 과거로
     * 되돌아간다.
     *
     * @return 이번 호출로 마지막 이탈 시각이 바뀌었으면 true
     */
    public boolean recordMediaLeave(Instant leftAt) {
        if (mediaLastLeftAt != null && !leftAt.isAfter(mediaLastLeftAt)) {
            return false;
        }
        this.mediaLastLeftAt = leftAt;
        return true;
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
