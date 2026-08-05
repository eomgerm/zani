package com.a105.zani.recording.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "recording.finalization.dispatch")
public record RecordingFinalizationDispatchProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("PT15S") Duration pollDelay,
        @DefaultValue("5") int batchSize,
        @DefaultValue("PT5H") Duration leaseDuration,
        @DefaultValue("PT15S") Duration waitingDelay,
        @DefaultValue("PT1M") Duration retryDelay,
        @DefaultValue("3") int maxAttempts) {

    public RecordingFinalizationDispatchProperties {
        if (pollDelay == null || pollDelay.isZero() || pollDelay.isNegative()) {
            throw new IllegalArgumentException("finalization poll delay must be positive");
        }
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("finalization lease duration must be positive");
        }
        if (waitingDelay == null || waitingDelay.isZero() || waitingDelay.isNegative()) {
            throw new IllegalArgumentException("finalization waiting delay must be positive");
        }
        if (retryDelay == null || retryDelay.isZero() || retryDelay.isNegative()) {
            throw new IllegalArgumentException("finalization retry delay must be positive");
        }
        if (batchSize < 1 || maxAttempts < 1) {
            throw new IllegalArgumentException("finalization batch size and max attempts must be positive");
        }
    }
}
