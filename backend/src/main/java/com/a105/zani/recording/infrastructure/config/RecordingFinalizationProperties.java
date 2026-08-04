package com.a105.zani.recording.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 최종 병합 worker의 파일 시스템·프로세스 설정. */
@ConfigurationProperties(prefix = "recording.finalization")
public record RecordingFinalizationProperties(
        @DefaultValue("/out") String sourceRoot,
        @DefaultValue("/finalized") String outputRoot,
        @DefaultValue("/app/media/finalize-recording.sh") String workerPath,
        @DefaultValue("PT4H") Duration processTimeout) {

    public RecordingFinalizationProperties {
        if (sourceRoot == null || sourceRoot.isBlank()) {
            throw new IllegalArgumentException("recording finalization source root is required");
        }
        if (outputRoot == null || outputRoot.isBlank()) {
            throw new IllegalArgumentException("recording finalization output root is required");
        }
        if (workerPath == null || workerPath.isBlank()) {
            throw new IllegalArgumentException("recording finalization worker path is required");
        }
        if (processTimeout == null || processTimeout.isZero() || processTimeout.isNegative()) {
            throw new IllegalArgumentException("recording finalization timeout must be positive");
        }
    }
}
