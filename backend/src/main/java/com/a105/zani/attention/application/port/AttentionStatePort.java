package com.a105.zani.attention.application.port;

import java.time.Duration;

import com.a105.zani.attention.domain.model.AttentionState;

/**
 * 참여도 판정 상태를 Redis에 보관하는 포트. 벤더(Redis) 타입은 인프라 어댑터 안에만 존재한다.
 *
 * <p>보관하는 것은 두 가지다. 하나는 참가자의 <b>현재 상태</b>(짧은 TTL, 측정 가능 비율 계산용), 다른 하나는 <b>최근 유의 상태 흔적</b>(5분 TTL, 코칭 트리거 분자·팁 유형 선택용).
 * 둘 다 TTL로 자연 소멸하므로 별도 정리가 필요 없다.
 */
public interface AttentionStatePort {

    /**
     * 이 판정 이벤트를 처음 받았는지 기록한다. 같은 참가자가 이미 보낸 clientEventId면 false를 돌려준다(원자적).
     *
     * <p>네트워크 재시도로 같은 이벤트가 두 번 와도 상태가 중복 반영되지 않게 하는 유일한 지점이다. clientEventId는 참가자 단위로만 유일하면 된다. 세션 단위로 묶으면 클라이언트가 창 시작
     * 시각 같은 결정적 값을 쓸 때 학생끼리 충돌한다.
     */
    boolean registerEvent(long sessionId, long participantId, String clientEventId, Duration ttl);

    /** 기록해 둔 이벤트 표시를 지운다. 뒤따르는 쓰기가 실패해 판정이 반영되지 않았을 때 재시도를 다시 받기 위한 되돌리기다. */
    void clearEvent(long sessionId, long participantId, String clientEventId);

    /** 참가자의 현재 판정 상태를 TTL과 함께 기록한다. 같은 참가자의 이전 상태는 덮어쓴다. */
    void recordCurrentState(long sessionId, long participantId, AttentionSnapshot snapshot, Duration ttl);

    /**
     * 유의 상태를 5분 창에 남긴다. 창 안에 한 번이라도 있었으면 트리거 분자에 든다(확정 문서 §4).
     *
     * <p>상태 종류별로 따로 남긴다. 팁 유형 선택이 CONFUSED·MISSED·NON_RESPONSE·UNMEASURABLE 네 비율을 각각 요구하므로, 한 참가자가 창 안에서 두 종류를 겪었다면 둘 다
     * 세어야 한다.
     *
     * <p><b>읽는 쪽 주의</b>: 트리거 분자는 네 키 집합에 있는 참가자 ID의 <b>합집합</b>이지 개수의 합이 아니다. 두 종류를 겪은 참가자를 두 번 세면 비율이 부풀어 30% 임계를 잘못
     * 넘긴다. 유형별 비율(팁 선택)만 상태별로 따로 센다.
     */
    void markSignificant(long sessionId, long participantId, AttentionState state, Duration window);
}
