package com.a105.zani.attention.domain.model.timeline;

/**
 * 확인 필요 비율이 높게 이어진 구간(FRD §18.3).
 *
 * <p>고정 구간이 아니라 시계열에서 동적으로 찾아낸다. 개념·챕터 경계 보정은 개념 구간 데이터가 아직 없어 이번 범위 밖이다.
 *
 * @param startSeconds 세션 시작 기준 시작 초. 임계를 넘긴 연속의 첫 점이다
 * @param endSeconds 세션 시작 기준 종료 초. 임계 아래로 내려간 연속의 첫 점이며, 열린 채 끝나면 마지막으로 비율이 있었던 점이다 — 판단 불가로 끝난 시간은 여기 들어가지 않는다
 */
public record DistractionInterval(long startSeconds, long endSeconds) {}
