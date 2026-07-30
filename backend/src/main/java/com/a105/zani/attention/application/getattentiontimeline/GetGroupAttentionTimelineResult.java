package com.a105.zani.attention.application.getattentiontimeline;

import java.util.List;

import com.a105.zani.attention.domain.model.timeline.DistractionInterval;
import com.a105.zani.attention.domain.model.timeline.GroupTimelinePoint;

/**
 * 강사용 익명 집단 타임라인.
 *
 * @param intervalSeconds 점 사이 간격
 * @param durationSeconds 타임라인이 덮는 길이. 세션 종료 시각을 우선하고, 과거 세션에 값이 없으면 마지막 관측 시각을 쓴다. 관측이 없으면 0 이다
 * @param points 5초 격자 점 목록. 관측이 없으면 빈 목록이다
 * @param distractedIntervals 확인 필요 비율이 높게 이어진 구간
 */
public record GetGroupAttentionTimelineResult(
        int intervalSeconds,
        long durationSeconds,
        List<GroupTimelinePoint> points,
        List<DistractionInterval> distractedIntervals) {}
