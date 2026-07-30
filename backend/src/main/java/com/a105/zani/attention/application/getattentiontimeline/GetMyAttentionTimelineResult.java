package com.a105.zani.attention.application.getattentiontimeline;

import java.util.List;

import com.a105.zani.attention.domain.model.timeline.FocusTimelinePoint;

/**
 * 학생 본인의 집중 흐름.
 *
 * @param intervalSeconds 점 사이 간격
 * @param durationSeconds 타임라인이 덮는 길이. 세션 종료 시각을 우선하고, 과거 세션에 값이 없으면 본인 마지막 관측 시각을 쓴다
 * @param points 5초 격자 점 목록. 관측이 없으면 빈 목록이다
 */
public record GetMyAttentionTimelineResult(
        int intervalSeconds, long durationSeconds, List<FocusTimelinePoint> points) {}
