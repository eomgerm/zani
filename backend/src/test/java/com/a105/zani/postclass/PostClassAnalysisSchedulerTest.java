package com.a105.zani.postclass;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.postclass.application.InMemoryPipelineJobPort;
import com.a105.zani.postclass.application.claimanalysis.TryClaimAnalysisService;
import com.a105.zani.postclass.application.claimanalysis.TryClaimAnalysisUseCase;
import com.a105.zani.postclass.application.exception.PipelineJobUnavailableException;
import com.a105.zani.postclass.application.port.PipelineJobPort;
import com.a105.zani.postclass.application.runsessionanalysis.RunSessionAnalysisUseCase;
import com.a105.zani.postclass.domain.model.PipelineStatus;
import com.a105.zani.postclass.infrastructure.config.PostClassAnalysisProperties;
import com.a105.zani.postclass.infrastructure.scheduler.PostClassAnalysisScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 디스패치 계약을 검증한다.
 *
 * <p>핵심은 <b>선점과 제출의 순서</b>다. 선점을 먼저 하고 제출이 거부되면 DB 는 임대가 잡힌 상태인데 실제로 도는 워커가 없어, 그 세션은 임대가 만료될 때까지 멈춘다. 그래서 실행기가 받은 뒤에
 * 선점하는지, 거부됐을 때 DB 가 정말 그대로인지를 실제 {@code TryClaimAnalysisService} 와 in-memory 작업 큐로 확인한다.
 */
class PostClassAnalysisSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-05T05:00:00Z");
    private static final Instant QUEUED_AT = NOW.minusSeconds(3_600);
    private static final Long FIRST = 9_304_401L;
    private static final Long SECOND = 9_304_402L;
    private static final Duration LEASE = Duration.ofMinutes(60);

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemoryPipelineJobPort jobPort = new InMemoryPipelineJobPort();
    private final List<Long> analysed = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();

    private final RunSessionAnalysisUseCase runAnalysis = sessionId -> {
        calls.add("run:" + sessionId);
        analysed.add(sessionId);
    };

    private TryClaimAnalysisUseCase tryClaim;

    @BeforeEach
    void setUp() {
        TryClaimAnalysisService real = new TryClaimAnalysisService(jobPort, clock);
        tryClaim = (sessionId, lease) -> {
            calls.add("claim:" + sessionId);
            return real.tryClaim(sessionId, lease);
        };
    }

    /**
     * 정해진 횟수만 받고 그 뒤로는 거부하는 실행기.
     *
     * <p>실제 실행기는 크기 1·큐 0 이라 이미 도는 작업이 있으면 제출이 거부된다. 그 상황을 결정적으로 재현하려고 수용 횟수를 고정한다.
     */
    private static final class BoundedExecutor implements Executor {
        private final int capacity;
        private int accepted;

        private BoundedExecutor(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public void execute(Runnable command) {
            if (accepted >= capacity) {
                throw new RejectedExecutionException("saturated");
            }
            accepted++;
            command.run();
        }
    }

    private PostClassAnalysisScheduler scheduler(Executor executor, PipelineJobPort port) {
        return new PostClassAnalysisScheduler(
                executor,
                port,
                tryClaim,
                runAnalysis,
                new PostClassAnalysisProperties(Duration.ofSeconds(10), 5, LEASE, true),
                clock);
    }

    private void givenAnalyzingJob(Long sessionId) {
        jobPort.enqueue(sessionId, QUEUED_AT);
        jobPort.updateStatus(sessionId, PipelineStatus.ANALYZING, QUEUED_AT);
    }

    @Test
    @DisplayName("후보를 실행기에 넘기고 선점에 성공하면 분석을 돌린다")
    void dispatches_and_runs_a_claimed_session() {
        givenAnalyzingJob(FIRST);

        scheduler(new BoundedExecutor(1), jobPort).dispatchDueAnalyses();

        assertThat(calls).containsExactly("claim:" + FIRST, "run:" + FIRST);
        assertThat(jobPort.nextAttemptAtOf(FIRST).orElseThrow()).isEqualTo(NOW.plus(LEASE));
    }

    /** 임대를 쥔 세션은 다른 실행이 건드리면 안 된다. 같은 GMS 호출이 두 번 나간다. */
    @Test
    @DisplayName("선점에 실패하면 분석을 돌리지 않는다")
    void does_not_run_when_the_claim_fails() {
        givenAnalyzingJob(FIRST);
        jobPort.claimAnalysis(FIRST, NOW.plusSeconds(1), NOW);

        scheduler(new BoundedExecutor(1), jobPort).dispatchDueAnalyses();

        assertThat(analysed).isEmpty();
    }

    /**
     * 선점은 실행기가 받은 <b>뒤에</b> 일어나야 한다.
     *
     * <p>순서를 뒤집으면 거부된 세션이 임대만 잡힌 채 남아, 만료될 때까지 아무도 그 세션을 다시 보지 않는다.
     */
    @Test
    @DisplayName("제출이 거부되면 선점도 일어나지 않고 DB 가 그대로다")
    void leaves_the_job_untouched_when_the_executor_is_saturated() {
        givenAnalyzingJob(FIRST);
        givenAnalyzingJob(SECOND);

        scheduler(new BoundedExecutor(1), jobPort).dispatchDueAnalyses();

        assertThat(calls).containsExactly("claim:" + FIRST, "run:" + FIRST);
        assertThat(jobPort.nextAttemptAtOf(SECOND)).isEmpty();
        assertThat(jobPort.attemptCountOf(SECOND)).contains(0);
    }

    /** 포화는 정상 동작이라 실패로 세지 않는다. 세면 한 번도 시도하지 않은 세션이 재시도 상한에 걸린다. */
    @Test
    @DisplayName("거부된 뒤에는 남은 후보를 더 제출하지 않는다")
    void stops_dispatching_after_the_first_rejection() {
        givenAnalyzingJob(FIRST);
        givenAnalyzingJob(SECOND);

        scheduler(new BoundedExecutor(1), jobPort).dispatchDueAnalyses();

        assertThat(calls).doesNotContain("claim:" + SECOND);
    }

    /** 스케줄러가 예외를 올리면 다음 주기가 통째로 사라진다. */
    @Test
    @DisplayName("후보 조회가 실패해도 예외가 새어 나가지 않는다")
    void swallows_a_candidate_query_failure() {
        PipelineJobPort failing = new InMemoryPipelineJobPort() {
            @Override
            public List<Long> findDueAnalysisSessionIds(Instant now, int limit) {
                throw new PipelineJobUnavailableException(new IllegalStateException("db down"));
            }
        };

        scheduler(new BoundedExecutor(1), failing).dispatchDueAnalyses();

        assertThat(analysed).isEmpty();
    }
}
