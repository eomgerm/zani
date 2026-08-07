package com.a105.zani.report.infrastructure.gms;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 질의응답 설정 등록(S15P11A105-259).
 *
 * <p>{@link ReportAnswerProperties} 를 조건 없이 여기서 등록한다. 실제 GMS 어댑터에 붙여 두면 그 빈이 {@code gms.mock-enabled=false} 조건부라 mock
 * 모드에서는 설정이 바인딩되지 않는다 — {@code ContentAnalysisConfig} 가 같은 이유로 분리되어 있다.
 */
@Configuration
@EnableConfigurationProperties(ReportAnswerProperties.class)
public class ReportAnswerConfig {}
