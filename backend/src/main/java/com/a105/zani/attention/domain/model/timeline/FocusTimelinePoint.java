package com.a105.zani.attention.domain.model.timeline;

/**
 * 5초 격자 한 점의 개인 집중 흐름.
 *
 * <p>평균 점수·타인 비교·모델 확률·검출기 단계를 담지 않는다(REPORT-S-010).
 *
 * @param offsetSeconds 세션 시작 기준 경과 초
 * @param focusPercent 0~100 정수 또는 {@code null}. 이름 그대로 퍼센트이며 강사 응답의 0.0~1.0 분수와 단위가 <b>다르다</b>
 * @param state 그 시각의 상태. 관측이 없거나 상태가 정해지지 않았으면 {@code null} 이다
 */
public record FocusTimelinePoint(long offsetSeconds, Integer focusPercent, StudentTimelineState state) {}
