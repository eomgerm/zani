package com.a105.zani.postclass.application.recordpipelinefailure;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.a105.zani.postclass.application.InMemoryPipelineJobPort;
import com.a105.zani.postclass.application.advancepipelinejob.AdvancePipelineJobCommand;
import com.a105.zani.postclass.application.advancepipelinejob.AdvancePipelineJobService;
import com.a105.zani.postclass.application.exception.PipelineJobNotFoundException;
import com.a105.zani.postclass.domain.model.PipelineStatus;
import com.a105.zani.postclass.domain.model.PostClassRetryPolicy;
import com.a105.zani.postclass.domain.model.RetryDecision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordPipelineFailureServiceTest {

    private static final long SESSION_ID = 800L;
    private static final Instant QUEUED_AT = Instant.parse("2026-07-30T09:00:00Z");
    private static final String REASON = "GMS 호출 한도 초과";

    private final InMemoryPipelineJobPort pipelineJobPort = new InMemoryPipelineJobPort();

    /** 마감까지 넉넉히 남은 시각에 동작하는 서비스. 시간 예산이 판단에 끼어들지 않게 한다. */
    private RecordPipelineFailureService serviceAt(Instant now) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        return new RecordPipelineFailureService(
                pipelineJobPort, new AdvancePipelineJobService(pipelineJobPort, clock), clock);
    }

    private RecordPipelineFailureService service() {
        return serviceAt(QUEUED_AT.plus(Duration.ofMinutes(10)));
    }

    @Test
    void keepsTheFailedStageAndSchedulesARetry() {
        givenJobAt(PipelineStatus.TRANSCRIBING);

        RecordPipelineFailureResult result = service().record(command(true));

        // 단계를 되돌리지 않는다 — 실패한 것은 현재 단계뿐이고, 되돌리면 8시간 예산만 줄어든다.
        assertEquals(PipelineStatus.TRANSCRIBING, result.status());
        assertTrue(result.willRetry());
        assertEquals(
                PipelineStatus.TRANSCRIBING,
                pipelineJobPort.statusOf(SESSION_ID).orElseThrow());
    }

    @Test
    void recordsTheAttemptAndTheReasonForTheNextDecision() {
        givenJobAt(PipelineStatus.TRANSCRIBING);

        RecordPipelineFailureResult result = service().record(command(true));

        assertEquals(1, pipelineJobPort.attemptCountOf(SESSION_ID).orElseThrow());
        assertEquals(REASON, pipelineJobPort.lastErrorOf(SESSION_ID).orElseThrow());
        assertEquals(
                result.nextAttemptAt(),
                pipelineJobPort.nextAttemptAtOf(SESSION_ID).orElseThrow());
    }

    @Test
    void failsTheJobThroughTheStateMachineWhenTheFailureWouldRepeat() {
        givenJobAt(PipelineStatus.ANALYZING);

        RecordPipelineFailureResult result = service().record(command(false));

        assertEquals(PipelineStatus.FAILED, result.status());
        assertFalse(result.willRetry());
        assertEquals(RetryDecision.GiveUpReason.NOT_RETRYABLE, result.giveUpReason());
        assertEquals(PipelineStatus.FAILED, pipelineJobPort.statusOf(SESSION_ID).orElseThrow());
    }

    @Test
    void keepsTheReasonAfterMovingToFailed() {
        givenJobAt(PipelineStatus.ANALYZING);

        service().record(command(false));

        // 단계 전이가 실패 사유를 지우면 운영에서 원인을 찾을 수 없다.
        assertEquals(REASON, pipelineJobPort.lastErrorOf(SESSION_ID).orElseThrow());
    }

    @Test
    void failsTheJobOnceTheAttemptsAreExhausted() {
        givenJobAt(PipelineStatus.TRANSCRIBING);

        RecordPipelineFailureResult result = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            result = service().record(command(true));
        }

        assertEquals(PipelineStatus.FAILED, result.status());
        assertEquals(RetryDecision.GiveUpReason.ATTEMPTS_EXHAUSTED, result.giveUpReason());
    }

    @Test
    void failsTheJobWhenTheNextAttemptWouldMissTheDeadline() {
        givenJobAt(PipelineStatus.VALIDATING);
        Instant justBeforeDeadline = PostClassRetryPolicy.deadline(QUEUED_AT).minus(Duration.ofMinutes(1));

        RecordPipelineFailureResult result = serviceAt(justBeforeDeadline).record(command(true));

        assertEquals(PipelineStatus.FAILED, result.status());
        assertEquals(RetryDecision.GiveUpReason.SLA_EXPIRED, result.giveUpReason());
    }

    @Test
    void givesEachStageItsOwnRetryBudget() {
        givenJobAt(PipelineStatus.TRANSCRIBING);
        service().record(command(true));
        assertEquals(1, pipelineJobPort.attemptCountOf(SESSION_ID).orElseThrow());

        // 다음 단계로 넘어가면 앞 단계에서 쓴 시도 횟수는 따라오지 않는다.
        advanceTo(PipelineStatus.ANALYZING);

        assertEquals(0, pipelineJobPort.attemptCountOf(SESSION_ID).orElseThrow());
        assertNull(pipelineJobPort.nextAttemptAtOf(SESSION_ID).orElse(null));
    }

    @Test
    void rejectsASessionThatHasNoJob() {
        assertThrows(PipelineJobNotFoundException.class, () -> service().record(command(true)));
    }

    private void givenJobAt(PipelineStatus status) {
        pipelineJobPort.enqueue(SESSION_ID, QUEUED_AT);
        if (status != PipelineStatus.QUEUED) {
            advanceTo(status);
        }
    }

    /** 상태 머신 규칙을 지켜 목표 단계까지 한 칸씩 옮긴다. */
    private void advanceTo(PipelineStatus target) {
        AdvancePipelineJobService advance =
                new AdvancePipelineJobService(pipelineJobPort, Clock.fixed(QUEUED_AT, ZoneOffset.UTC));
        for (PipelineStatus stage : List.of(
                PipelineStatus.TRANSCRIBING,
                PipelineStatus.ANALYZING,
                PipelineStatus.VALIDATING,
                PipelineStatus.PUBLISHED)) {
            advance.advance(new AdvancePipelineJobCommand(SESSION_ID, stage));
            if (stage == target) {
                return;
            }
        }
    }

    private static RecordPipelineFailureCommand command(boolean retryable) {
        return new RecordPipelineFailureCommand(SESSION_ID, REASON, retryable);
    }
}
