package com.a105.zani.attention.domain.model.timeline;

/**
 * 겹치지 않는 30초 구간 하나의 익명 집단 집중 흐름.
 *
 * <p>학생 식별자와 학생별 값은 어떤 필드로도 담지 않는다(REPORT-I-002 · ALERT-004).
 *
 * @param offsetSeconds 칸의 시작 시각
 * @param focusLevel 값이 있는 <b>집계 대상</b> 학생들의 단계 평균 1.00~4.00 또는 {@code null}. 집계 대상이 5명 미만이거나 기여할 학생이 없으면 {@code null}
 *     이다
 * @param eligibleCount 그 칸에 걸친 5초 스냅샷 {@code eligibleCount} 의 <b>최솟값</b>(설계 문서 §2.10). 최댓값이나 평균을 쓰면 인원이 적었던 순간이 공개된다
 */
public record GroupFocusBucket(long offsetSeconds, Double focusLevel, int eligibleCount) {}
