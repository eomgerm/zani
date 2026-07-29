package com.a105.zani.audioclip.infrastructure.gms;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

import com.a105.zani.audioclip.application.port.AudioTranscriptionPort;
import com.a105.zani.audioclip.infrastructure.transcription.StubAudioTranscriptionAdapter;
import com.a105.zani.common.infrastructure.gms.GmsProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code gms.mock-enabled} 스위치로 전사 어댑터가 정확히 하나만 등록되는지 검증한다.
 *
 * <p>두 어댑터가 같은 포트를 구현하므로 조건이 겹치면 빈 중복으로 기동이 실패하고, 둘 다 빠지면 코칭 트리거가 런타임에 NoSuchBeanDefinitionException 으로 깨진다. 두 사고 모두 기동
 * 이후에나 드러나서 단정으로 못 박아 둔다. (S15P11A105-203)
 */
class TranscriptionAdapterSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TranscriptionAdaptersConfig.class)
            .withPropertyValues("gms.base-url=https://gms.test", "gms.api-key=test-key", "gms.stt-model=whisper-1");

    @Test
    void registersStubOnlyWhenMockEnabled() {
        runner.withPropertyValues("gms.mock-enabled=true").run(context -> {
            assertThat(context).hasSingleBean(AudioTranscriptionPort.class);
            assertThat(context).hasSingleBean(StubAudioTranscriptionAdapter.class);
            assertThat(context).doesNotHaveBean(GmsAudioTranscriptionAdapter.class);
        });
    }

    @Test
    void registersGmsAdapterOnlyWhenMockDisabled() {
        runner.withPropertyValues("gms.mock-enabled=false").run(context -> {
            assertThat(context).hasSingleBean(AudioTranscriptionPort.class);
            assertThat(context).hasSingleBean(GmsAudioTranscriptionAdapter.class);
            assertThat(context).doesNotHaveBean(StubAudioTranscriptionAdapter.class);
        });
    }

    @Test
    void fallsBackToStubWhenSwitchIsAbsent() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AudioTranscriptionPort.class);
            assertThat(context).hasSingleBean(StubAudioTranscriptionAdapter.class);
        });
    }

    /** 두 어댑터만 올린다. GMS 를 실제로 호출하지 않으므로 client 는 구성만 되면 된다. */
    @Configuration(proxyBeanMethods = false)
    @Import({StubAudioTranscriptionAdapter.class, GmsAudioTranscriptionAdapter.class})
    @EnableConfigurationProperties(GmsProperties.class)
    static class TranscriptionAdaptersConfig {

        @Bean
        RestClient gmsTranscriptionRestClient() {
            return RestClient.builder().baseUrl("https://gms.test").build();
        }
    }
}
