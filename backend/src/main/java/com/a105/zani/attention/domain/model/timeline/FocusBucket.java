package com.a105.zani.attention.domain.model.timeline;

/**
 * 겹치지 않는 30초 구간 하나의 집중 흐름 값.
 *
 * @param offsetSeconds 칸의 시작 시각
 * @param focusLevel 1.00~4.00 또는 {@code null}. {@code null} 은 값 없음이며 1단계가 아니다
 */
public record FocusBucket(long offsetSeconds, Double focusLevel) {}
