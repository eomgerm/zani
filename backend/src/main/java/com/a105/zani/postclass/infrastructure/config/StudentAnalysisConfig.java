package com.a105.zani.postclass.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 학생별 분석 설정을 등록한다.
 *
 * <p>조건부 어댑터({@code gms.mock-enabled=false})에 붙여 두면 mock 모드에서 설정이 바인딩되지 않는다. {@code CoachPipelineConfig} 가 같은 이유로 팁 설정을
 * 분리해 등록한다.
 *
 * <p>요청 바이트 임계는 여기에 없다 — 게이트웨이 실측에서 나온 값이라 환경별로 달라질 이유가 없고, 서비스의 상수다.
 */
@Configuration
@EnableConfigurationProperties(StudentAnalysisProperties.class)
public class StudentAnalysisConfig {}
