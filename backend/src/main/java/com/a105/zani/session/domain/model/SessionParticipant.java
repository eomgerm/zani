package com.a105.zani.session.domain.model;

import java.time.Instant;

/**
 * 세션 참가 관계. 강사도 세션 생성 시점에 하나 갖는다(가이드 §5).
 *
 * <p>두 종류의 "입장"을 구분해서 담는다. API 입장(이 행이 생기는 시점)은 강의실 화면에 들어갈 자격을 뜻할 뿐이고, 출석과 사후 자료 접근 자격을 확정하는 것은 LiveKit에 실제로 연결됐을
 * 때다(가이드 §5). 그래서 {@code firstJoinedAt}은 행 생성 시점에 비어 있고 {@code participant_joined} webhook에서만 채워진다. API만 호출하고 미디어에 접속하지
 * 않은 사용자를 집계 분모에서 빼려면 이 구분이 필요하다.
 */
public class SessionParticipant {

    private final Long id;
    private final Long sessionId;
    private final Long userId;
    private final SessionParticipantRole role;
    private Instant firstJoinedAt;
    private Instant lastJoinedAt;
    private Instant lastLeftAt;
    private Instant lastAccessedAt;

    private SessionParticipant(
            Long id,
            Long sessionId,
            Long userId,
            SessionParticipantRole role,
            Instant firstJoinedAt,
            Instant lastJoinedAt,
            Instant lastLeftAt,
            Instant lastAccessedAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.userId = userId;
        this.role = role;
        this.firstJoinedAt = firstJoinedAt;
        this.lastJoinedAt = lastJoinedAt;
        this.lastLeftAt = lastLeftAt;
        this.lastAccessedAt = lastAccessedAt;
    }

    /**
     * API 입장·세션 생성으로 참가 관계를 만든다. 아직 미디어에 접속하지 않았으므로 입장 시각들은 비어 있다.
     *
     * @param enrolledAt 참가 관계가 생긴 시각. 접근 이력으로만 남는다.
     */
    public static SessionParticipant enroll(
            Long id, Long sessionId, Long userId, SessionParticipantRole role, Instant enrolledAt) {
        return new SessionParticipant(id, sessionId, userId, role, null, null, null, enrolledAt);
    }

    public static SessionParticipant reconstitute(
            Long id,
            Long sessionId,
            Long userId,
            SessionParticipantRole role,
            Instant firstJoinedAt,
            Instant lastJoinedAt,
            Instant lastLeftAt,
            Instant lastAccessedAt) {
        return new SessionParticipant(
                id, sessionId, userId, role, firstJoinedAt, lastJoinedAt, lastLeftAt, lastAccessedAt);
    }

    /**
     * LiveKit 연결 성공을 기록한다. 최초 입장 시각은 한 번 정해지면 재접속으로 바뀌지 않는다.
     *
     * @return 이번 호출이 최초 입장을 확정했으면 true, 재접속이면 false
     */
    public boolean confirmMediaJoin(Instant at) {
        this.lastJoinedAt = at;
        this.lastAccessedAt = at;
        if (firstJoinedAt != null) {
            return false;
        }
        this.firstJoinedAt = at;
        return true;
    }

    /** LiveKit 이탈을 기록한다. 재입장 자격과 사후 접근 자격은 취소하지 않는다(가이드 §6). */
    public void recordMediaLeft(Instant at) {
        this.lastLeftAt = at;
    }

    public void recordAccess(Instant accessedAt) {
        this.lastAccessedAt = accessedAt;
    }

    /** 실제 미디어 연결에 성공한 적이 있는가. 출석·사후 자료 접근 자격과 집계 분모의 기준이다. */
    public boolean hasJoinedMedia() {
        return firstJoinedAt != null;
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

    public Instant lastJoinedAt() {
        return lastJoinedAt;
    }

    public Instant lastLeftAt() {
        return lastLeftAt;
    }

    public Instant lastAccessedAt() {
        return lastAccessedAt;
    }
}
