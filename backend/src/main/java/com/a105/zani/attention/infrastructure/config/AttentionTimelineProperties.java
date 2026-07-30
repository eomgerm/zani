package com.a105.zani.attention.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.a105.zani.attention.domain.model.timeline.TimelinePolicy;

/**
 * 참여도 타임라인 임계값 설정.
 *
 * <p>비워 둔 값은 {@link TimelinePolicy#defaults()} 를 쓴다. 설정 파일이 모든 값을 다시 적지 않아도 되게 하려는 것이며, 기본값의 출처는 코드 한 곳으로 남는다.
 *
 * <p>흐트러짐 임계값은 FRD §18.3 이 원격 조정을 요구해 설정으로 뺐다. 나머지는 실시간 경로와 값을 맞춰야 하는 상수라 운영 중 바꿀 일이 드물지만, 한쪽만 바꾸면 같은 순간에 실시간과 리포트가 다른
 * 판단을 하게 되므로 함께 노출해 둔다.
 */
@ConfigurationProperties(prefix = "attention.timeline")
public record AttentionTimelineProperties(
        Duration samplingInterval,
        Duration groupWindow,
        Duration focusWindow,
        Duration connectionGap,
        Duration requiredConnection,
        Duration measurementOutage,
        Duration significantTtl,
        Integer unmeasurableRunLength,
        Double focusCoverageFloor,
        Integer minimumEligible,
        Double distractionStartRatio,
        Duration distractionStartHold,
        Double distractionEndRatio,
        Duration distractionEndHold,
        Duration distractionMergeGap) {

    public AttentionTimelineProperties {
        TimelinePolicy defaults = TimelinePolicy.defaults();
        if (samplingInterval == null) {
            samplingInterval = defaults.samplingInterval();
        }
        if (groupWindow == null) {
            groupWindow = defaults.groupWindow();
        }
        if (focusWindow == null) {
            focusWindow = defaults.focusWindow();
        }
        if (connectionGap == null) {
            connectionGap = defaults.connectionGap();
        }
        if (requiredConnection == null) {
            requiredConnection = defaults.requiredConnection();
        }
        if (measurementOutage == null) {
            measurementOutage = defaults.measurementOutage();
        }
        if (significantTtl == null) {
            significantTtl = defaults.significantTtl();
        }
        if (unmeasurableRunLength == null) {
            unmeasurableRunLength = defaults.unmeasurableRunLength();
        }
        if (focusCoverageFloor == null) {
            focusCoverageFloor = defaults.focusCoverageFloor();
        }
        if (minimumEligible == null) {
            minimumEligible = defaults.minimumEligible();
        }
        if (distractionStartRatio == null) {
            distractionStartRatio = defaults.distractionStartRatio();
        }
        if (distractionStartHold == null) {
            distractionStartHold = defaults.distractionStartHold();
        }
        if (distractionEndRatio == null) {
            distractionEndRatio = defaults.distractionEndRatio();
        }
        if (distractionEndHold == null) {
            distractionEndHold = defaults.distractionEndHold();
        }
        if (distractionMergeGap == null) {
            distractionMergeGap = defaults.distractionMergeGap();
        }
    }
}
