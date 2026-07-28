package com.a105.zani.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 애플리케이션 전역 스케줄링 활성화. 도메인별로 중복 활성화하지 않도록 이 설정이 단일 소유한다. */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
