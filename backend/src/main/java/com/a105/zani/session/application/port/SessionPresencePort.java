package com.a105.zani.session.application.port;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 세션 참가자 presence를 Redis TTL로 보관하는 포트. 벤더(Redis) 타입은 인프라 어댑터 안에만 존재한다. presence 키는 heartbeat마다 갱신되어 TTL이 지나면 자동 소멸하고,
 * 강사 유예는 마감 시각을 값으로 보관한다.
 */
public interface SessionPresencePort {

    /** 참가자 presence 키를 주어진 TTL로 기록/갱신한다(현재 접속 중). */
    void recordHeartbeat(long sessionId, long participantId, Duration ttl);

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
}
