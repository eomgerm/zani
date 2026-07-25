package com.a105.zani.recording.infrastructure.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(RecordingProperties.class)
public class RecordingConfig {

    /** 녹화 시작 시각 기록 등 시간 판단의 기준 시계. 다른 모듈이 이미 등록했다면 그것을 쓴다. */
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
