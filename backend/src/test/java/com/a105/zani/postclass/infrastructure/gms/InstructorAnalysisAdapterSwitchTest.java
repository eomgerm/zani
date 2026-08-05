package com.a105.zani.postclass.infrastructure.gms;

import org.junit.jupiter.api.DisplayName;
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
import com.a105.zani.postclass.application.port.InstructorAnalysisPort;
import com.a105.zani.postclass.infrastructure.config.InstructorAnalysisProperties;

import static org.assertj.core.api.Assertions.assertThat;

/** 조건이 겹치면 빈 중복으로 기동이 실패하고, 둘 다 빠지면 호출 시점에 깨진다. 둘 다 기동 이후에나 드러난다. */
class InstructorAnalysisAdapterSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AnalysisAdaptersConfig.class)
            .withPropertyValues(
                    "gms.base-url=https://gms.test", "gms.api-key=test-key", "gms.analysis-model=gpt-5.4-mini");

    @Test
    @DisplayName("mock 스위치가 켜지면 mock 만 뜬다")
    void registers_the_mock_only_when_enabled() {
        runner.withPropertyValues("gms.mock-enabled=true").run(context -> {
            assertThat(context).hasSingleBean(InstructorAnalysisPort.class);
            assertThat(context).hasSingleBean(GmsInstructorAnalysisMockAdapter.class);
            assertThat(context).doesNotHaveBean(GmsInstructorAnalysisHttpAdapter.class);
        });
    }

    @Test
    @DisplayName("mock 스위치가 꺼지면 실제 어댑터만 뜬다")
    void registers_the_http_adapter_only_when_disabled() {
        runner.withPropertyValues("gms.mock-enabled=false").run(context -> {
            assertThat(context).hasSingleBean(InstructorAnalysisPort.class);
            assertThat(context).hasSingleBean(GmsInstructorAnalysisHttpAdapter.class);
            assertThat(context).doesNotHaveBean(GmsInstructorAnalysisMockAdapter.class);
        });
    }

    @Test
    @DisplayName("스위치가 없으면 mock 으로 떨어진다 — 실수로 크레딧을 쓰지 않는다")
    void falls_back_to_the_mock() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(InstructorAnalysisPort.class);
            assertThat(context).hasSingleBean(GmsInstructorAnalysisMockAdapter.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({GmsInstructorAnalysisMockAdapter.class, GmsInstructorAnalysisHttpAdapter.class})
    @EnableConfigurationProperties({GmsProperties.class, InstructorAnalysisProperties.class})
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
