package com.a105.zani.attention.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.a105.zani.attention.domain.model.CoachingTriggerPolicy;

/** 코칭 트리거 임계값을 도메인 정책 타입으로 바꿔 넘긴다. 판정이 스프링 설정 타입을 직접 알면 도메인이 프레임워크에 묶인다. */
@Configuration
@EnableConfigurationProperties(CoachingTriggerProperties.class)
public class CoachingTriggerConfig {

    @Bean
    public CoachingTriggerPolicy coachingTriggerPolicy(CoachingTriggerProperties properties) {
        return new CoachingTriggerPolicy(properties.threshold(), properties.cooldown(), properties.minimumAudio());
    }
}
