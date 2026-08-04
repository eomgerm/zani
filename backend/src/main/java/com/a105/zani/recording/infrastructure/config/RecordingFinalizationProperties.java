package com.a105.zani.recording.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 최종 병합 산출물을 쓰는 컨테이너 내부 경로. Track Egress 원본의 읽기 전용 루트와 분리한다. */
@ConfigurationProperties(prefix = "recording.finalization")
public record RecordingFinalizationProperties(
        @DefaultValue("/finalized") String outputRoot) {}
