package com.a105.zani.postclass.application.advancepipelinejob;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.a105.zani.postclass.application.InMemoryPipelineJobPort;
import com.a105.zani.postclass.application.exception.IllegalPipelineTransitionException;
import com.a105.zani.postclass.application.exception.PipelineJobNotFoundException;
import com.a105.zani.postclass.domain.model.PipelineStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancePipelineJobServiceTest {

    private static final long SESSION_ID = 700L;
    private static final Instant QUEUED_AT = Instant.parse("2026-07-30T09:30:00Z");
    private static final Instant NOW = QUEUED_AT.plusSeconds(1_800);

    private final InMemoryPipelineJobPort pipelineJobPort = new InMemoryPipelineJobPort();

    private AdvancePipelineJobService service;

    @BeforeEach
    void setUp() {
        service = new AdvancePipelineJobService(pipelineJobPort, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void movesAQueuedJobToTheNextStage() {
        givenQueuedJob();

        AdvancePipelineJobResult result = service.advance(command(PipelineStatus.TRANSCRIBING));

        assertEquals(PipelineStatus.TRANSCRIBING, result.status());
        assertTrue(result.advancedNow());
        assertEquals(
                PipelineStatus.TRANSCRIBING,
                pipelineJobPort.statusOf(SESSION_ID).orElseThrow());
    }

    @Test
    void recordsWhenTheStageChangedSoTheSlaClockCanBeRead() {
        givenQueuedJob();

        service.advance(command(PipelineStatus.TRANSCRIBING));

        assertEquals(NOW, pipelineJobPort.changedAtOf(SESSION_ID).orElseThrow());
    }

    @Test
    void runsThroughEveryStageUpToPublished() {
        givenQueuedJob();

        service.advance(command(PipelineStatus.TRANSCRIBING));
        service.advance(command(PipelineStatus.ANALYZING));
        service.advance(command(PipelineStatus.VALIDATING));
        AdvancePipelineJobResult result = service.advance(command(PipelineStatus.PUBLISHED));

        assertEquals(PipelineStatus.PUBLISHED, result.status());
        assertTrue(result.advancedNow());
    }

    @Test
    void treatsARepeatedStageReportAsSuccessWithoutMovingAgain() {
        givenQueuedJob();
        service.advance(command(PipelineStatus.TRANSCRIBING));

        AdvancePipelineJobResult result = service.advance(command(PipelineStatus.TRANSCRIBING));

        assertEquals(PipelineStatus.TRANSCRIBING, result.status());
        // 워커 재시작·중복 실행에서 늘 일어난다. 다음 단계 시작 같은 후속 작업은 이 값이 true 인 호출자만 해야 한 번으로 유지된다.
        assertFalse(result.advancedNow());
    }

    @Test
    void rejectsAStageThatSkipsThePipelineOrder() {
        givenQueuedJob();

        assertThrows(
                IllegalPipelineTransitionException.class, () -> service.advance(command(PipelineStatus.ANALYZING)));
        assertEquals(PipelineStatus.QUEUED, pipelineJobPort.statusOf(SESSION_ID).orElseThrow());
    }

    @Test
    void failsAJobFromTheStageItStoppedAt() {
        givenQueuedJob();
        service.advance(command(PipelineStatus.TRANSCRIBING));

        AdvancePipelineJobResult result = service.advance(command(PipelineStatus.FAILED));

        assertEquals(PipelineStatus.FAILED, result.status());
        assertTrue(result.advancedNow());
    }

    @Test
    void rejectsAnyStageAfterTheJobIsPublished() {
        givenQueuedJob();
        service.advance(command(PipelineStatus.TRANSCRIBING));
        service.advance(command(PipelineStatus.ANALYZING));
        service.advance(command(PipelineStatus.VALIDATING));
        service.advance(command(PipelineStatus.PUBLISHED));

        // 공개된 결과를 되돌리는 경로는 없다.
        assertThrows(IllegalPipelineTransitionException.class, () -> service.advance(command(PipelineStatus.FAILED)));
    }

    @Test
    void rejectsAnyStageAfterTheJobFailed() {
        givenQueuedJob();
        service.advance(command(PipelineStatus.FAILED));

        // 실패한 작업을 다시 돌릴지는 재시도 정책(S15P11A105-107)이 정한다.
        assertThrows(
                IllegalPipelineTransitionException.class, () -> service.advance(command(PipelineStatus.TRANSCRIBING)));
    }

    @Test
    void rejectsASessionThatHasNoJobYet() {
        assertThrows(PipelineJobNotFoundException.class, () -> service.advance(command(PipelineStatus.TRANSCRIBING)));
    }

    private void givenQueuedJob() {
        pipelineJobPort.enqueue(SESSION_ID, QUEUED_AT);
    }

    private static AdvancePipelineJobCommand command(PipelineStatus targetStatus) {
        return new AdvancePipelineJobCommand(SESSION_ID, targetStatus);
    }
}
