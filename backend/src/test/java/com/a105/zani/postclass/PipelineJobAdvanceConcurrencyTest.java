package com.a105.zani.postclass;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.a105.zani.postclass.application.advancepipelinejob.AdvancePipelineJobCommand;
import com.a105.zani.postclass.application.advancepipelinejob.AdvancePipelineJobUseCase;
import com.a105.zani.postclass.domain.model.PipelineStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 사후 처리 작업의 단계 전이가 여러 워커가 동시에 보고해도 한 번만 일어나는지 확인한다. 실제 MySQL 의 행 잠금을 거쳐야 의미가 있으므로 로컬 MySQL/Redis 가 떠 있어야 통과한다.
 *
 * <p>대역으로 확인한 멱등·순서 규칙은 {@code AdvancePipelineJobServiceTest} 에 있다. 여기서는 두 트랜잭션을 진짜로 부딪힌다.
 */
@SpringBootTest
class PipelineJobAdvanceConcurrencyTest {

    private static final long JOB_ID = 9_301_030L;
    private static final long SESSION_ID = 9_301_031L;

    @Autowired
    private AdvancePipelineJobUseCase advancePipelineJobUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Instant queuedAt;

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @BeforeEach
    void setUp() {
        queuedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(3_600);
        cleanUpRows();
        insertQueuedJob();
    }

    @AfterEach
    void tearDown() {
        cleanUpRows();
    }

    @Test
    void onlyOneConcurrentReportMovesTheJobToTheNextStage() throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger movers = new AtomicInteger();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        for (int worker = 0; worker < 2; worker++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (advancePipelineJobUseCase
                            .advance(new AdvancePipelineJobCommand(SESSION_ID, PipelineStatus.TRANSCRIBING))
                            .advancedNow()) {
                        movers.incrementAndGet();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException exception) {
                    failure.compareAndSet(null, exception);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "단계 전이 경합이 제 시간에 끝나지 않았습니다");
        executor.shutdown();

        if (failure.get() != null) {
            throw failure.get();
        }
        // 뒤에 온 쪽은 실패가 아니라 중복 보고로 물러나야 한다. 실패로 돌려주면 멀쩡한 수업의 결과가 끊긴다.
        assertEquals(1, movers.get());
        assertEquals(PipelineStatus.TRANSCRIBING.name(), currentStatus());
    }

    @Test
    void recordsTheStageChangeTimeInsteadOfLeavingTheQueuedTime() {
        advancePipelineJobUseCase.advance(new AdvancePipelineJobCommand(SESSION_ID, PipelineStatus.TRANSCRIBING));

        // 벌크 UPDATE 는 @LastModifiedDate 리스너를 타지 않는다. updated_at 이 등록 시각에 머물면
        // 8시간 SLA(AI-006)를 재는 쪽이 어느 단계에서 멈췄는지 알 수 없다.
        assertTrue(changedAt().isAfter(queuedAt), "단계 변경 시각이 등록 시각에 머물렀습니다");
    }

    private String currentStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM pipeline_jobs WHERE session_id = ?", String.class, SESSION_ID);
    }

    private Instant changedAt() {
        return jdbcTemplate
                .queryForObject(
                        "SELECT updated_at FROM pipeline_jobs WHERE session_id = ?", LocalDateTime.class, SESSION_ID)
                .toInstant(ZoneOffset.UTC);
    }

    private void insertQueuedJob() {
        jdbcTemplate.update(
                "INSERT INTO pipeline_jobs (id, session_id, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                JOB_ID,
                SESSION_ID,
                PipelineStatus.QUEUED.name(),
                utc(queuedAt),
                utc(queuedAt));
    }

    private void cleanUpRows() {
        jdbcTemplate.update("DELETE FROM pipeline_jobs WHERE session_id = ?", SESSION_ID);
    }
}
