package com.a105.zani.postclass.application.claimanalysis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.postclass.application.InMemoryPipelineJobPort;
import com.a105.zani.postclass.domain.model.PipelineStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 대역으로 판정 규칙만 본다. 두 트랜잭션이 실제로 부딪히는 경우는 {@code PipelineJobAnalysisQueryTest} 가 실제 MySQL 로 확인한다. */
class TryClaimAnalysisServiceTest {

    private static final long SESSION_ID = 9_304_100L;
    private static final Instant QUEUED_AT = Instant.parse("2026-08-05T01:00:00Z");
    private static final Instant NOW = QUEUED_AT.plusSeconds(1_800);
    private static final Duration LEASE = Duration.ofMinutes(60);

    private final InMemoryPipelineJobPort pipelineJobPort = new InMemoryPipelineJobPort();

    private TryClaimAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new TryClaimAnalysisService(pipelineJobPort, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** 전사가 방금 넘긴 작업이다. 대기 시각이 비어 있는 것이 "아무도 안 잡음" 이다. */
    @Test
    @DisplayName("대기 시각이 없는 분석 작업을 잡는다")
    void claims_an_analyzing_job_without_a_wait() {
        givenAnalyzingJob(null);

        assertTrue(service.tryClaim(SESSION_ID, LEASE));
        assertEquals(
                NOW.plus(LEASE), pipelineJobPort.nextAttemptAtOf(SESSION_ID).orElseThrow());
    }

    @Test
    @DisplayName("임대가 끝났거나 재시도 기한이 지난 작업을 잡는다")
    void claims_a_job_whose_wait_has_passed() {
        givenAnalyzingJob(NOW.minusSeconds(1));

        assertTrue(service.tryClaim(SESSION_ID, LEASE));
        assertEquals(
                NOW.plus(LEASE), pipelineJobPort.nextAttemptAtOf(SESSION_ID).orElseThrow());
    }

    /** 선점은 실패가 아니다. 시도 횟수를 올리면 한 번도 실패하지 않은 세션이 재시도 상한에 걸린다. */
    @Test
    @DisplayName("선점은 시도 횟수와 단계를 건드리지 않는다")
    void claiming_preserves_the_retry_budget_and_the_stage() {
        givenAnalyzingJob(NOW.minusSeconds(1));
        pipelineJobPort.markRetry(SESSION_ID, "GMS_UNAVAILABLE", NOW.minusSeconds(1), NOW.minusSeconds(600));
        int before = pipelineJobPort.attemptCountOf(SESSION_ID).orElseThrow();

        service.tryClaim(SESSION_ID, LEASE);

        assertEquals(before, pipelineJobPort.attemptCountOf(SESSION_ID).orElseThrow());
        assertEquals(
                PipelineStatus.ANALYZING, pipelineJobPort.statusOf(SESSION_ID).orElseThrow());
    }

    @Test
    @DisplayName("임대가 살아 있으면 잡지 않는다 — 다른 실행이 처리 중이다")
    void refuses_a_job_whose_lease_is_still_alive() {
        givenAnalyzingJob(NOW.plusSeconds(1));

        assertFalse(service.tryClaim(SESSION_ID, LEASE));
        assertEquals(
                NOW.plusSeconds(1), pipelineJobPort.nextAttemptAtOf(SESSION_ID).orElseThrow());
    }

    @Test
    @DisplayName("분석 단계가 아니면 잡지 않는다")
    void refuses_a_job_in_another_stage() {
        pipelineJobPort.enqueue(SESSION_ID, QUEUED_AT);
        pipelineJobPort.updateStatus(SESSION_ID, PipelineStatus.VALIDATING, QUEUED_AT);

        assertFalse(service.tryClaim(SESSION_ID, LEASE));
    }

    /** 후보 조회와 이 호출 사이에 작업이 사라졌다. 예외로 올리면 스케줄 주기가 통째로 끊긴다. */
    @Test
    @DisplayName("작업이 없으면 조용히 물러난다")
    void refuses_quietly_when_the_job_is_gone() {
        assertFalse(service.tryClaim(SESSION_ID, LEASE));
    }

    private void givenAnalyzingJob(Instant nextAttemptAt) {
        pipelineJobPort.enqueue(SESSION_ID, QUEUED_AT);
        pipelineJobPort.updateStatus(SESSION_ID, PipelineStatus.ANALYZING, QUEUED_AT);
        if (nextAttemptAt != null) {
            pipelineJobPort.claimAnalysis(SESSION_ID, nextAttemptAt, QUEUED_AT);
        }
    }
}
