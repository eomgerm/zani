package com.a105.zani.postclass.infrastructure.gms;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 공통 분석 설정 등록(S15P11A105-248).
 *
 * <p>{@link ContentAnalysisProperties} 를 조건 없이 여기서 등록한다. 실제 GMS 어댑터에 붙여 두면 그 빈이 {@code gms.mock-enabled=false} 조건부라
 * mock 모드에서는 설정이 바인딩되지 않는다 — coach 의 {@code CoachPipelineConfig} 가 같은 이유로 팁 설정을 별도 {@code @Configuration} 에서 등록한다.
 */
@Configuration
@EnableConfigurationProperties(ContentAnalysisProperties.class)
public class ContentAnalysisConfig {}
