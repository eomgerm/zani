package com.a105.zani.attention.application.getattentiontimeline;

import java.util.List;

import com.a105.zani.attention.domain.model.timeline.DistractionInterval;
import com.a105.zani.attention.domain.model.timeline.GroupFocusBucket;
import com.a105.zani.attention.domain.model.timeline.GroupSignalPoint;
import com.a105.zani.attention.domain.model.timeline.SectionFocusAverage;

/**
 * 강사용 익명 집단 타임라인.
 *
 * <p>격자가 둘이라 배열이 나뉜다. 한 배열에 섞으면 30초 값이 5초 점 6개 중 5개에서 {@code null} 이 되고, 그 {@code null} 이 "값 없음" 인지 "이 격자에 속하지 않음" 인지
 * 구분되지 않는다(설계 문서 §3.1).
 *
 * @param durationSeconds 타임라인이 덮는 길이. 세션 종료 시각을 우선하고, 과거 세션에 값이 없으면 마지막 관측 시각을 쓴다. 관측이 없으면 0 이다
 * @param focusIntervalSeconds 집중 흐름 칸 크기(30초)
 * @param focusBuckets 겹치지 않는 30초 칸 목록. 주 계열이다
 * @param signalIntervalSeconds 신호 점 간격(5초)
 * @param signalPoints 5초 격자 신호 비율 목록. 보조 계열이며 흐트러짐 판정의 근거다
 * @param distractedIntervals 확인 필요 비율이 높게 이어진 구간
 * @param sections 수업 내용 구간별 집중 흐름 평균. 248 이 채우기 전에는 빈 목록이다
 */
public record GetGroupAttentionTimelineResult(
        long durationSeconds,
        int focusIntervalSeconds,
        List<GroupFocusBucket> focusBuckets,
        int signalIntervalSeconds,
        List<GroupSignalPoint> signalPoints,
        List<DistractionInterval> distractedIntervals,
        List<SectionFocusAverage> sections) {}
