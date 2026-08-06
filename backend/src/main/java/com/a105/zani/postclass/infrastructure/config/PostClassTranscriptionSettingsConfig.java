package com.a105.zani.postclass.infrastructure.config;

import java.nio.file.Path;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.a105.zani.common.infrastructure.gms.GmsProperties;
import com.a105.zani.postclass.application.port.PostClassTranscriptionSettings;
import com.a105.zani.postclass.application.port.TranscriptFilterSettings;

/**
 * 설정을 application 계층 타입으로 옮긴다.
 *
 * <p>전사 언어만 {@code gms} 설정에서 온다. 사후 전사가 자기 언어 설정을 따로 두지 않는 이유는 값이 갈리면 요청과 문서에 적히는 언어가 달라지기 때문이다 — GMS 로 보내는 값이 정본이다.
 */
@Configuration
public class PostClassTranscriptionSettingsConfig {

    @Bean
    public PostClassTranscriptionSettings postClassTranscriptionSettings(
            PostClassTranscriptionProperties properties, GmsProperties gmsProperties) {
        return new PostClassTranscriptionSettings(
                Path.of(properties.sourceRoot()),
                Path.of(properties.workDir()),
                properties.leaseDuration(),
                properties.concurrency(),
                gmsProperties.transcribeLanguage(),
                properties.silencePrefilterEnabled());
    }

    /**
     * 조립 단계 필터 설정.
     *
     * <p>{@link PostClassTranscriptionSettings} 에 얹지 않고 따로 두는 이유는 소비자가 다르기 때문이다. 그쪽은 오케스트레이션이 쓰는 값(원본 루트·작업
     * 디렉터리·lease·동시성)이고 조립은 그중 아무것도 필요하지 않다. 한 record 에 합치면 조립 유스케이스가 파일 경로와 lease 기간을 주입받게 된다.
     */
    @Bean
    public TranscriptFilterSettings transcriptFilterSettings(PostClassTranscriptionProperties properties) {
        return new TranscriptFilterSettings(properties.hallucinationFilterEnabled(), properties.noSpeechThreshold());
    }
}
