package com.a105.zani.attention.domain.model.timeline;

/**
 * 수업 내용 구간 하나의 집중 흐름 평균.
 *
 * @param startSeconds 구간 시작 초
 * @param endSeconds 구간 종료 초
 * @param title 구간 제목
 * @param focusLevel 그 구간 안 30초 칸 값들의 단순 평균 1.00~4.00. 값이 하나도 없으면 {@code null} 이다
 */
public record SectionFocusAverage(long startSeconds, long endSeconds, String title, Double focusLevel) {}
