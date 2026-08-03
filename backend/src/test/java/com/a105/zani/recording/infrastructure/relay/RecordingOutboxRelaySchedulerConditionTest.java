package com.a105.zani.recording.infrastructure.relay;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.a105.zani.recording.application.orchestrate.RelayRecordingOutboxUseCase;

import static org.assertj.core.api.Assertions.assertThat;

class RecordingOutboxRelaySchedulerConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(RelayRecordingOutboxUseCase.class, () -> () -> 0)
            .withUserConfiguration(RecordingOutboxRelayScheduler.class);

    @Test
    void enablesSchedulerByDefault() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(RecordingOutboxRelayScheduler.class));
    }

    @Test
    void disablesSchedulerWhenConfigured() {
        contextRunner
                .withPropertyValues("recording.outbox-relay-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RecordingOutboxRelayScheduler.class));
    }
}
