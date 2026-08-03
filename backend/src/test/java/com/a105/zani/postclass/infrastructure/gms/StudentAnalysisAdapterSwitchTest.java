package com.a105.zani.postclass.infrastructure.gms;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.a105.zani.common.infrastructure.gms.GmsProperties;
import com.a105.zani.postclass.application.port.StudentAnalysisPort;
import com.a105.zani.postclass.infrastructure.config.StudentAnalysisProperties;

import static org.assertj.core.api.Assertions.assertThat;

/** 조건이 겹치면 빈 중복으로 기동이 실패하고, 둘 다 빠지면 호출 시점에 깨진다. 둘 다 기동 이후에나 드러난다. */
class StudentAnalysisAdapterSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AnalysisAdaptersConfig.class)
            .withPropertyValues(
                    "gms.base-url=https://gms.test", "gms.api-key=test-key", "gms.analysis-model=gpt-5.4-mini");

    @Test
    void registersMockOnlyWhenMockEnabled() {
        runner.withPropertyValues("gms.mock-enabled=true").run(context -> {
            assertThat(context).hasSingleBean(StudentAnalysisPort.class);
            assertThat(context).hasSingleBean(GmsStudentAnalysisMockAdapter.class);
            assertThat(context).doesNotHaveBean(GmsStudentAnalysisHttpAdapter.class);
        });
    }

    @Test
    void registersHttpAdapterOnlyWhenMockDisabled() {
        runner.withPropertyValues("gms.mock-enabled=false").run(context -> {
            assertThat(context).hasSingleBean(StudentAnalysisPort.class);
            assertThat(context).hasSingleBean(GmsStudentAnalysisHttpAdapter.class);
            assertThat(context).doesNotHaveBean(GmsStudentAnalysisMockAdapter.class);
        });
    }

    @Test
    void fallsBackToMockWhenSwitchIsAbsent() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(StudentAnalysisPort.class);
            assertThat(context).hasSingleBean(GmsStudentAnalysisMockAdapter.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({GmsStudentAnalysisMockAdapter.class, GmsStudentAnalysisHttpAdapter.class})
    @EnableConfigurationProperties({GmsProperties.class, StudentAnalysisProperties.class})
    static class AnalysisAdaptersConfig {

        @Bean
        RestClient gmsAnalysisRestClient() {
            return RestClient.builder().baseUrl("https://gms.test").build();
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }
    }
}
