package com.a105.zani.session.application.port;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 세션 참가자 presence를 Redis TTL로 보관하는 포트. 벤더(Redis) 타입은 인프라 어댑터 안에만 존재한다. presence 키는 heartbeat마다 갱신되어 TTL이 지나면 자동 소멸하고,
 * 강사 유예는 마감 시각을 값으로 보관한다.
 */
public interface SessionPresencePort {

    /**
     * 참가자 presence 키를 주어진 TTL로 기록/갱신하고, 이 연속 접속이 언제 시작됐는지를 돌려준다(현재 접속 중).
     *
     * <p>시작 시각은 <b>처음 한 번만</b> 심고 이후 heartbeat 는 TTL 만 늘린다. 매번 새로 심으면 "연속 접속 1분"이 영원히 채워지지 않아 아무도 집계 분모에 들어오지 못한다(확정 문서
     * §7). 읽고-쓰기로 나누지 않는 이유도 같다 — 같은 참가자의 heartbeat 가 겹쳐 들어오면 둘 다 자기가 시작이라고 판단한다.
     *
     * @param now 이 heartbeat 시각. 진행 중인 연속 접속이 없을 때 시작 시각으로 심는다
     * @return 이 연속 접속의 시작 시각
     */
    Instant recordHeartbeat(long sessionId, long participantId, Instant now, Duration ttl);

    /** 참가자 presence 키를 제거한다(연결 종료·재연결 시도). */
    void clearPresence(long sessionId, long participantId);

    /**
     * 강사 유예를 시작한다. 진행 중인 유예가 없을 때만 마감 시각을 기록하는 원자적 연산(SETNX 계열)이라, 강사가 동시에 여러 연결로 끊겨도 마감 시각이 갱신되지 않는다. 이미 유예가 진행 중이면 아무
     * 것도 하지 않는다.
     */
    void startInstructorGrace(long sessionId, Instant deadline, Duration ttl);

    /** 강사 유예 창을 제거한다(복귀 또는 종료 처리 후). */
    void clearInstructorGrace(long sessionId);

    /** 진행 중인 강사 유예 마감 시각. 유예 창이 없으면 비어 있다. */
    Optional<Instant> instructorGraceDeadline(long sessionId);

    /**
     * 주어진 참가자들 중 지금 접속 중인 사람의 연속 접속 시작 시각.
     *
     * <p>접속이 끊긴 참가자는 결과에 없다 — presence 키가 TTL 로 사라졌기 때문이다. 후보를 밖에서 받는 이유는 키 공간을 훑지 않기 위해서다. 세션 참가자 목록은 DB 가 알고 있으므로
     * SCAN 없이 그 ID 들만 조회한다.
     */
    Map<Long, Instant> connectedSince(long sessionId, Collection<Long> participantIds);
}
