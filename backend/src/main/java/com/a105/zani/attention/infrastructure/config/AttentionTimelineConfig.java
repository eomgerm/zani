package com.a105.zani.attention.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.a105.zani.attention.domain.model.timeline.TimelinePolicy;

/** 타임라인 임계값을 도메인 정책 타입으로 바꿔 넘긴다. 계산기가 스프링 설정 타입을 직접 알면 도메인이 프레임워크에 묶인다. */
@Configuration
@EnableConfigurationProperties(AttentionTimelineProperties.class)
public class AttentionTimelineConfig {

    @Bean
    public TimelinePolicy timelinePolicy(AttentionTimelineProperties properties) {
        return new TimelinePolicy(
                properties.samplingInterval(),
                properties.groupWindow(),
                properties.focusWindow(),
                properties.connectionGap(),
                properties.requiredConnection(),
                properties.measurementOutage(),
                properties.significantTtl(),
                properties.unmeasurableRunLength(),
                properties.focusCoverageFloor(),
                properties.minimumEligible(),
                properties.distractionStartRatio(),
                properties.distractionStartHold(),
                properties.distractionEndRatio(),
                properties.distractionEndHold(),
                properties.distractionMergeGap());
    }
}
