package com.a105.zani.postclass.application.alertoverduejobs;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import com.a105.zani.postclass.application.InMemoryPipelineJobPort;
import com.a105.zani.postclass.application.advancepipelinejob.AdvancePipelineJobCommand;
import com.a105.zani.postclass.application.advancepipelinejob.AdvancePipelineJobService;
import com.a105.zani.postclass.domain.model.PipelineStatus;
import com.a105.zani.postclass.domain.model.PostClassRetryPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AlertOverduePipelineJobsServiceTest {

    private static final long OVERDUE_SESSION = 900L;
    private static final long FRESH_SESSION = 901L;
    private static final Instant QUEUED_AT = Instant.parse("2026-07-30T09:00:00Z");
    /** 마감 직후. QUEUED_AT 에 등록된 작업만 늦은 것으로 잡혀야 한다. */
    private static final Instant AFTER_DEADLINE = QUEUED_AT.plus(PostClassRetryPolicy.SLA);

    private final InMemoryPipelineJobPort pipelineJobPort = new InMemoryPipelineJobPort();
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private AlertOverduePipelineJobsService serviceAt(Instant now) {
        return new AlertOverduePipelineJobsService(pipelineJobPort, Clock.fixed(now, ZoneOffset.UTC), meterRegistry);
    }

    @Test
    void reportsAJobThatPassedTheDeadlineWithoutFinishing() {
        pipelineJobPort.enqueue(OVERDUE_SESSION, QUEUED_AT);

        assertEquals(1, serviceAt(AFTER_DEADLINE).alertOverdueJobs());
    }

    @Test
    void ignoresAJobThatIsStillWithinTheDeadline() {
        pipelineJobPort.enqueue(FRESH_SESSION, AFTER_DEADLINE.minus(Duration.ofHours(1)));

        assertEquals(0, serviceAt(AFTER_DEADLINE).alertOverdueJobs());
    }

    @Test
    void ignoresAJobThatAlreadyFinished() {
        pipelineJobPort.enqueue(OVERDUE_SESSION, QUEUED_AT);
        publish(OVERDUE_SESSION);

        // 이미 결과가 나온 작업은 늦었더라도 더 볼 것이 없다.
        assertEquals(0, serviceAt(AFTER_DEADLINE).alertOverdueJobs());
    }

    @Test
    void publishesTheOverdueCountAsAGauge() {
        pipelineJobPort.enqueue(OVERDUE_SESSION, QUEUED_AT);
        serviceAt(AFTER_DEADLINE).alertOverdueJobs();

        assertEquals(
                1.0,
                meterRegistry.get("postclass.pipeline.overdue.jobs").gauge().value());
    }

    private void publish(long sessionId) {
        AdvancePipelineJobService advance =
                new AdvancePipelineJobService(pipelineJobPort, Clock.fixed(QUEUED_AT, ZoneOffset.UTC));
        for (PipelineStatus stage : new PipelineStatus[] {
            PipelineStatus.TRANSCRIBING, PipelineStatus.ANALYZING, PipelineStatus.VALIDATING, PipelineStatus.PUBLISHED
        }) {
            advance.advance(new AdvancePipelineJobCommand(sessionId, stage));
        }
    }
}
