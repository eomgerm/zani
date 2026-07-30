package com.a105.zani.attention.domain.model.timeline;

import java.time.Duration;

/**
 * 타임라인 계산에 쓰는 임계값 묶음.
 *
 * <p>값의 출처는 두 곳이다 — FRD §18.2·§18.3·§19.2 와 확정 문서 §6.2·§7.1·§7.3. 계산기들이 상수를 각자 들고 있으면 한 곳만 고쳐지는 사고가 나므로 하나로 모은다.
 *
 * @param maxDuration 시계열이 다룰 수 있는 최대 길이. 세션 자동 종료 시각과 같은 3시간이며 안전장치로 둔다 — 오프셋이 한 건이라도 망가지면 격자 수가 그대로 응답 크기가 된다
 * @param samplingInterval 격자 간격. 이 간격마다 시계열 점을 하나 찍는다
 * @param groupWindow 집단 비율이 참고하는 창. 이 창을 채우지 못한 구간은 비율을 내지 않는다
 * @param focusWindow 개인 집중 흐름의 이동창
 * @param connectionGap 이보다 길게 이벤트가 끊기면 접속이 끊긴 것으로 본다
 * @param requiredConnection 이만큼 이어서 접속해야 집계 대상이 된다
 * @param measurementOutage 측정 불가가 이만큼 이어지면 분모에서 뺀다
 * @param significantTtl 확정된 유의 상태가 유효하게 남는 기간
 * @param unmeasurableRunLength 참여 상태 UNMEASURABLE 을 확정하는 연속 관측 수
 * @param focusCoverageFloor 개인 창에서 측정 가능 시간이 이 비율 미만이면 값을 내지 않는다
 * @param minimumEligible 집계 대상이 이 수 미만이면 비율을 숨긴다(REPORT-I-005)
 * @param distractionStartRatio 흐트러짐 구간을 여는 확인 필요 비율의 하한
 * @param distractionStartHold 여는 비율이 이만큼 이어져야 구간이 열린다
 * @param distractionEndRatio 흐트러짐 구간을 닫는 확인 필요 비율의 상한
 * @param distractionEndHold 닫는 비율이 이만큼 이어져야 구간이 닫힌다
 * @param distractionMergeGap 두 구간의 간격이 이보다 짧으면 하나로 합친다
 */
public record TimelinePolicy(
        Duration maxDuration,
        Duration samplingInterval,
        Duration groupWindow,
        Duration focusWindow,
        Duration connectionGap,
        Duration requiredConnection,
        Duration measurementOutage,
        Duration significantTtl,
        int unmeasurableRunLength,
        double focusCoverageFloor,
        int minimumEligible,
        double distractionStartRatio,
        Duration distractionStartHold,
        double distractionEndRatio,
        Duration distractionEndHold,
        Duration distractionMergeGap) {

    public static TimelinePolicy defaults() {
        return new TimelinePolicy(
                // session 도메인의 Session.ACTIVE_DURATION 과 같은 값이다. 다른 도메인의 모델을 직접 참조하지
                // 않으려고 값을 여기 다시 적는다.
                Duration.ofHours(3),
                Duration.ofSeconds(5),
                Duration.ofMinutes(5),
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                Duration.ofMinutes(1),
                Duration.ofMinutes(1),
                Duration.ofMinutes(5),
                3,
                0.7d,
                5,
                0.30d,
                Duration.ofSeconds(20),
                0.20d,
                Duration.ofSeconds(30),
                Duration.ofSeconds(15));
    }
}
