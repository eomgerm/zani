package com.a105.zani.session.domain.model;

/**
 * {@code interaction_events.event_type} 에 저장하는 값.
 *
 * <p>손들기를 올림·내림 두 값으로 나눈 이유: 리포트가 "얼마나 오래 들고 있었는지"를 재려면 짝을 맞춰야 하는데, 한 종류에 {@code payload} 로 방향을 넣으면 집계 때마다 JSON 을 파헤쳐야
 * 한다. 컬럼 값으로 갈라 두면 SQL 로 끝난다.
 *
 * <p>강사 제어(66)가 자기 값을 여기에 추가한다.
 */
public enum InteractionEventType {
    HAND_RAISED,
    HAND_LOWERED,
    REACTION
}
