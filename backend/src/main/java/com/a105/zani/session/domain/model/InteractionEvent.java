package com.a105.zani.session.domain.model;

import java.util.Map;

/**
 * 수업 중 일어난 상호작용 한 건. 손들기·반응·강사 제어가 같은 테이블에 종류만 달리해 쌓인다.
 *
 * <p>현재 상태(지금 손을 든 사람)와는 별개다. 상태는 Redis 가 들고 있고 이 기록은 리포트가 읽는다. 둘을 한곳에 두면 실시간 조회가 DB 를 때리거나, 이력이 TTL 로 사라진다.
 *
 * <p>{@code occurredOffsetMs} 는 수업 시작으로부터 흐른 밀리초다. 리포트 타임라인이 이 값을 축으로 쓰므로 클라이언트가 보낸 시각이 아니라 서버가 계산한 값만 담는다.
 */
public class InteractionEvent {

    private final Long id;
    private final Long sessionId;
    private final Long actorParticipantId;
    private final InteractionEventType type;
    private final long occurredOffsetMs;
    private final Map<String, Object> payload;

    private InteractionEvent(
            Long id,
            Long sessionId,
            Long actorParticipantId,
            InteractionEventType type,
            long occurredOffsetMs,
            Map<String, Object> payload) {
        this.id = id;
        this.sessionId = sessionId;
        this.actorParticipantId = actorParticipantId;
        this.type = type;
        this.occurredOffsetMs = occurredOffsetMs;
        this.payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static InteractionEvent record(
            Long id,
            Long sessionId,
            Long actorParticipantId,
            InteractionEventType type,
            long occurredOffsetMs,
            Map<String, Object> payload) {
        if (occurredOffsetMs < 0) {
            throw new IllegalArgumentException("상호작용 시각은 수업 시작보다 이를 수 없습니다.");
        }
        return new InteractionEvent(id, sessionId, actorParticipantId, type, occurredOffsetMs, payload);
    }

    public static InteractionEvent reconstitute(
            Long id,
            Long sessionId,
            Long actorParticipantId,
            InteractionEventType type,
            long occurredOffsetMs,
            Map<String, Object> payload) {
        return new InteractionEvent(id, sessionId, actorParticipantId, type, occurredOffsetMs, payload);
    }

    public Long id() {
        return id;
    }

    public Long sessionId() {
        return sessionId;
    }

    public Long actorParticipantId() {
        return actorParticipantId;
    }

    public InteractionEventType type() {
        return type;
    }

    public long occurredOffsetMs() {
        return occurredOffsetMs;
    }

    public Map<String, Object> payload() {
        return payload;
    }
}
