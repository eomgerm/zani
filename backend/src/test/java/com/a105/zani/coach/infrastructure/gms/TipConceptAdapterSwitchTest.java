package com.a105.zani.coach.infrastructure.gms;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.a105.zani.coach.application.port.TipConceptPort;
import com.a105.zani.coach.infrastructure.config.CoachTipProperties;
import com.a105.zani.common.infrastructure.gms.GmsProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code gms.mock-enabled} 스위치로 팁 개념 어댑터가 정확히 하나만 등록되는지 검증한다.
 *
 * <p>203의 전사 어댑터와 같은 이유다 — 조건이 겹치면 빈 중복으로 기동이 실패하고, 둘 다 빠지면 트리거 시점에 NoSuchBeanDefinitionException 으로 깨진다. 둘 다 기동 이후에나
 * 드러난다.
 */
class TipConceptAdapterSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TipAdaptersConfig.class)
            .withPropertyValues("gms.base-url=https://gms.test", "gms.api-key=test-key", "gms.tip-model=gpt-5.4-mini");

    @Test
    void registersMockOnlyWhenMockEnabled() {
        runner.withPropertyValues("gms.mock-enabled=true").run(context -> {
            assertThat(context).hasSingleBean(TipConceptPort.class);
            assertThat(context).hasSingleBean(GmsTipConceptMockAdapter.class);
            assertThat(context).doesNotHaveBean(GmsTipConceptHttpAdapter.class);
        });
    }

    @Test
    void registersHttpAdapterOnlyWhenMockDisabled() {
        runner.withPropertyValues("gms.mock-enabled=false").run(context -> {
            assertThat(context).hasSingleBean(TipConceptPort.class);
            assertThat(context).hasSingleBean(GmsTipConceptHttpAdapter.class);
            assertThat(context).doesNotHaveBean(GmsTipConceptMockAdapter.class);
        });
    }

    @Test
    void fallsBackToMockWhenSwitchIsAbsent() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(TipConceptPort.class);
            assertThat(context).hasSingleBean(GmsTipConceptMockAdapter.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({GmsTipConceptMockAdapter.class, GmsTipConceptHttpAdapter.class})
    @EnableConfigurationProperties({GmsProperties.class, CoachTipProperties.class})
    static class TipAdaptersConfig {

        @Bean
        RestClient gmsTipRestClient() {
            return RestClient.builder().baseUrl("https://gms.test").build();
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }
    }
}
