package com.a105.zani.attention.application.getattentiontimeline;

import java.util.List;

import com.a105.zani.attention.domain.model.timeline.FocusBucket;
import com.a105.zani.attention.domain.model.timeline.SectionFocusAverage;
import com.a105.zani.attention.domain.model.timeline.StateInterval;

/**
 * 학생 본인의 집중 흐름.
 *
 * <p>신호 비율 배열이 없다. 확인 필요 비율은 집단 값이라 개인 화면에 의미가 없고, 내려보내면 타인 정보가 된다.
 *
 * <p>평균 점수·타인 비교·모델 확률을 담지 않는다(REPORT-S-010). 단계 값 자체는 준다 — FRD §11.7 의 단계 비노출 제한은 실시간 화면에만 적용된다.
 *
 * @param durationSeconds 타임라인이 덮는 길이. 세션 종료 시각을 우선하고, 과거 세션에 값이 없으면 본인 마지막 관측 시각을 쓴다
 * @param focusIntervalSeconds 집중 흐름 칸 크기(30초)
 * @param focusBuckets 겹치지 않는 30초 칸 목록. 관측이 없으면 빈 목록이다
 * @param stateIntervals 인접 동일 상태를 병합한 구간 목록. 관측이 없던 시간은 구간을 만들지 않아 사이가 빌 수 있다
 * @param sections 수업 내용 구간별 집중 흐름 평균. 본인 칸 값으로 계산한다
 */
public record GetMyAttentionTimelineResult(
        long durationSeconds,
        int focusIntervalSeconds,
        List<FocusBucket> focusBuckets,
        List<StateInterval> stateIntervals,
        List<SectionFocusAverage> sections) {}
