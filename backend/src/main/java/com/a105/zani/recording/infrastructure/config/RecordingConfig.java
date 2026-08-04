package com.a105.zani.recording.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 녹화 설정. 시간 판단 기준 시계({@code Clock})는 세션 도메인이 이미 빈으로 제공하므로 여기서 중복 정의하지 않는다. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({RecordingProperties.class, RecordingFinalizationProperties.class})
public class RecordingConfig {}
