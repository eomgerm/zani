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
import org.junit.jupiter.api.Test;

import com.a105.zani.postclass.application.InMemoryPipelineJobPort;
import com.a105.zani.postclass.application.exception.PipelineJobUnavailableException;
import com.a105.zani.postclass.application.port.PipelineJobPort;
import com.a105.zani.postclass.application.recoverstalledtranscriptions.RecoverStalledTranscriptionsService;
import com.a105.zani.postclass.application.starttranscription.TryStartTranscriptionService;
import com.a105.zani.postclass.application.starttranscription.TryStartTranscriptionUseCase;
import com.a105.zani.postclass.application.transcribesession.TranscribeSessionUseCase;
import com.a105.zani.postclass.domain.model.PipelineStatus;
import com.a105.zani.postclass.infrastructure.config.PostClassTranscriptionProperties;
import com.a105.zani.postclass.infrastructure.scheduler.PostClassTranscriptionScheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 디스패치 계약을 검증한다.
 *
 * <p>핵심은 <b>선점과 제출의 순서</b>다. 선점을 먼저 하고 제출이 거부되면 DB 는 "실행 중" 인데 아무도 그 세션을 다시 보지 않아 작업이 영구 정지한다. 그래서 실행기가 받은 뒤에 선점하는지,
 * 거부됐을 때 DB 가 정말 그대로인지를 실제 {@code TryStartTranscriptionService} 와 in-memory 작업 큐로 확인한다.
 */
class PostClassTranscriptionSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-04T05:00:00Z");
    private static final Long FIRST = 9_600_001L;
    private static final Long SECOND = 9_600_002L;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemoryPipelineJobPort jobPort = new InMemoryPipelineJobPort();
    private final List<Long> transcribed = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();

    private TryStartTranscriptionUseCase tryStart;

    @BeforeEach
    void setUp() {
        TryStartTranscriptionService real = new TryStartTranscriptionService(jobPort, clock);
        tryStart = sessionId -> {
            calls.add("tryStart:" + sessionId);
            return real.tryStart(sessionId);
        };
    }

    /**
     * 정해진 횟수만 받고 그 뒤로는 거부하는 실행기.
     *
     * <p>실제 실행기는 크기 1·큐 0 이라 이미 도는 작업이 있으면 제출이 거부된다. 그 상황을 결정적으로 재현하려고 수용 횟수를 고정한다 — 실제 실행기를 쓰면 첫 작업이 즉시 끝나 슬롯이 비어, 두
     * 번째가 받아들여질 수도 있고 아닐 수도 있다.
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

    private PostClassTranscriptionScheduler scheduler(Executor executor) {
        return scheduler(executor, jobPort);
    }

    private PostClassTranscriptionScheduler scheduler(Executor executor, PipelineJobPort port) {
        TranscribeSessionUseCase transcribe = sessionId -> {
            calls.add("transcribe:" + sessionId);
            transcribed.add(sessionId);
        };
        PostClassTranscriptionProperties properties = new PostClassTranscriptionProperties(
                "/srv/source",
                "/tmp/work",
                "ffmpeg",
                "ffprobe",
                Duration.ofSeconds(60),
                Duration.ofSeconds(10),
                5,
                true,
                Duration.ofMinutes(10),
                Duration.ofMinutes(5),
                25_165_824L,
                2,
                false);
        return new PostClassTranscriptionScheduler(executor, port, tryStart, transcribe, properties, clock);
    }

    private void enqueue(Long sessionId, int minutesAgo) {
        jobPort.enqueue(sessionId, NOW.minus(Duration.ofMinutes(minutesAgo)));
    }

    private void enqueue(Long sessionId) {
        enqueue(sessionId, 30);
    }

    /** 전사 단계에서 실패해 재시도 기한이 지난 작업. */
    private void enqueueRetryDue(Long sessionId) {
        enqueue(sessionId);
        jobPort.updateStatus(sessionId, PipelineStatus.TRANSCRIBING, NOW.minus(Duration.ofMinutes(20)));
        jobPort.markRetry(
                sessionId, "GMS timeout", NOW.minus(Duration.ofMinutes(1)), NOW.minus(Duration.ofMinutes(10)));
    }

    @Test
    void 실행기가_포화면_선점을_시도하지_않는다() {
        // 선점을 먼저 하면 거부 시점에 DB 가 "실행 중" 으로 남아 아무도 다시 보지 않는다.
        enqueue(FIRST);

        scheduler(new BoundedExecutor(0)).dispatchDueTranscriptions();

        assertTrue(calls.isEmpty(), "실행기가 받지 않았으면 선점도 없어야 한다: " + calls);
    }

    @Test
    void 거부된_QUEUED_작업은_그대로_남아_다음_주기에_다시_발견된다() {
        enqueue(FIRST);

        scheduler(new BoundedExecutor(0)).dispatchDueTranscriptions();

        assertEquals(PipelineStatus.QUEUED, jobPort.statusOf(FIRST).orElseThrow());
        assertEquals(List.of(FIRST), jobPort.findDueTranscriptionSessionIds(NOW, 5));
    }

    @Test
    void 거부된_재시도_작업의_재시도_대기가_지워지지_않는다() {
        // clearRetryWait 가 불렸다면 next_attempt_at 이 사라져 findDue 가 다시 담지 않는다.
        enqueueRetryDue(FIRST);

        scheduler(new BoundedExecutor(0)).dispatchDueTranscriptions();

        assertTrue(jobPort.nextAttemptAtOf(FIRST).isPresent(), "재시도 대기가 남아 있어야 한다");
        assertEquals(PipelineStatus.TRANSCRIBING, jobPort.statusOf(FIRST).orElseThrow());
        assertEquals(List.of(FIRST), jobPort.findDueTranscriptionSessionIds(NOW, 5));
    }

    @Test
    void 실행기가_받은_경우에만_선점하고_전사한다() {
        enqueue(FIRST);

        scheduler(new BoundedExecutor(1)).dispatchDueTranscriptions();

        assertEquals(List.of("tryStart:" + FIRST, "transcribe:" + FIRST), calls, "선점이 전사보다 앞선다");
        assertEquals(PipelineStatus.TRANSCRIBING, jobPort.statusOf(FIRST).orElseThrow());
    }

    @Test
    void 대기_작업이_여럿이어도_실행권은_하나만_얻는다() {
        // 실행기가 크기 1·큐 0 이므로 한 주기에 하나만 시작된다. 나머지는 QUEUED 로 남아 다음 주기로 간다.
        // 등록 시각을 벌려 둔다 — findDue 는 오래된 것부터 담으므로 같은 시각이면 순서가 확정되지 않는다.
        enqueue(FIRST, 30);
        enqueue(SECOND, 20);

        scheduler(new BoundedExecutor(1)).dispatchDueTranscriptions();

        assertEquals(List.of(FIRST), transcribed);
        assertEquals(PipelineStatus.TRANSCRIBING, jobPort.statusOf(FIRST).orElseThrow());
        assertEquals(PipelineStatus.QUEUED, jobPort.statusOf(SECOND).orElseThrow());
        assertEquals(List.of(SECOND), jobPort.findDueTranscriptionSessionIds(NOW, 5));
    }

    @Test
    void 제출_거부를_파이프라인_실패로_기록하지_않는다() {
        // 포화는 정상 동작이다. 실패로 기록하면 한 세션이 도는 동안 다른 세션들의 5회 예산이 주기마다 깎인다.
        enqueue(FIRST);
        enqueue(SECOND);

        scheduler(new BoundedExecutor(0)).dispatchDueTranscriptions();

        assertEquals(0, jobPort.attemptCountOf(FIRST).orElseThrow());
        assertEquals(0, jobPort.attemptCountOf(SECOND).orElseThrow());
        assertTrue(jobPort.lastErrorOf(FIRST).isEmpty(), "실패 사유가 남아서는 안 된다");
        assertTrue(jobPort.lastErrorOf(SECOND).isEmpty());
    }

    @Test
    void 선점에_실패하면_전사를_시작하지_않는다() {
        // 조회와 선점 사이에 다른 실행이 가져갔거나 작업이 다음 단계로 넘어간 경우다. 후보 목록은 이미
        // 떠 있으므로, 그 사이에 상태가 바뀐 상황을 후보를 그대로 돌려주는 대역으로 만든다.
        enqueue(FIRST);
        jobPort.updateStatus(FIRST, PipelineStatus.ANALYZING, NOW.minusSeconds(60));
        PipelineJobPort staleCandidate = new InMemoryPipelineJobPort() {
            @Override
            public List<Long> findDueTranscriptionSessionIds(Instant now, int limit) {
                return List.of(FIRST);
            }
        };

        scheduler(new BoundedExecutor(1), staleCandidate).dispatchDueTranscriptions();

        assertEquals(List.of("tryStart:" + FIRST), calls, "선점만 시도하고 전사는 부르지 않는다");
        assertTrue(transcribed.isEmpty());
        assertEquals(PipelineStatus.ANALYZING, jobPort.statusOf(FIRST).orElseThrow(), "단계를 되돌리지 않는다");
    }

    @Test
    void 재기동_복구가_고아_작업을_다시_발견되게_만든다() {
        // 워커 없이 남은 TRANSCRIBING + next_attempt_at=null 은 후보 조회가 "실행 중" 으로 보고 제외한다.
        // 복구 없이는 청크 lease 가 만료돼도 회수할 세션이 디스패치되지 않아 영구 정지한다.
        enqueue(FIRST);
        assertTrue(tryStart.tryStart(FIRST), "최초 시작이 TRANSCRIBING 으로 옮기고 대기 시각을 비운다");
        calls.clear();
        assertEquals(List.of(), jobPort.findDueTranscriptionSessionIds(NOW, 5), "복구 전에는 후보에 없다");

        int recovered = new RecoverStalledTranscriptionsService(jobPort, clock).recover();

        assertEquals(1, recovered);
        assertEquals(List.of(FIRST), jobPort.findDueTranscriptionSessionIds(NOW, 5), "복구 후에는 후보에 있다");

        // 수동 markRetry 없이 스케줄러가 그대로 이어간다.
        scheduler(new BoundedExecutor(1)).dispatchDueTranscriptions();

        assertEquals(List.of(FIRST), transcribed);
        assertEquals(PipelineStatus.TRANSCRIBING, jobPort.statusOf(FIRST).orElseThrow());
    }

    @Test
    void 재기동_복구가_시도_횟수를_올리지_않는다() {
        // 크래시는 단계 실패가 아니다. 올리면 배포 한 번에 재시도 예산이 깎여, 한 번도 실패하지 않은
        // 세션이 상한에 걸린다.
        enqueue(FIRST);
        tryStart.tryStart(FIRST);
        int before = jobPort.attemptCountOf(FIRST).orElseThrow();

        new RecoverStalledTranscriptionsService(jobPort, clock).recover();

        assertEquals(before, jobPort.attemptCountOf(FIRST).orElseThrow());
    }

    @Test
    void 재기동_복구가_재시도_대기_중인_작업은_건드리지_않는다() {
        // 이미 후보인 작업이다. 대기 시각을 지금으로 당기면 백오프가 무의미해진다.
        enqueueRetryDue(FIRST);
        Instant scheduled = jobPort.nextAttemptAtOf(FIRST).orElseThrow();

        int recovered = new RecoverStalledTranscriptionsService(jobPort, clock).recover();

        assertEquals(0, recovered);
        assertEquals(scheduled, jobPort.nextAttemptAtOf(FIRST).orElseThrow());
    }

    @Test
    void 후보_조회가_실패해도_주기가_끊기지_않는다() {
        // 예외를 올리면 fixedDelay 스케줄이 통째로 멈춘다. 그러면 저장소가 회복돼도 전사가 다시 시작되지 않는다.
        InMemoryPipelineJobPort unreadable = new InMemoryPipelineJobPort() {
            @Override
            public List<Long> findDueTranscriptionSessionIds(Instant now, int limit) {
                throw new PipelineJobUnavailableException(new IllegalStateException("db down"));
            }
        };

        scheduler(new BoundedExecutor(1), unreadable).dispatchDueTranscriptions();

        assertTrue(calls.isEmpty());
        assertTrue(transcribed.isEmpty());
    }
}
