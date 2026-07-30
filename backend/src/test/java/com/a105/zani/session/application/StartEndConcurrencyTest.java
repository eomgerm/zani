package com.a105.zani.session.application;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.session.application.end.EndSessionByInstructorCommand;
import com.a105.zani.session.application.end.EndSessionByInstructorUseCase;
import com.a105.zani.session.application.start.StartSessionCommand;
import com.a105.zani.session.application.start.StartSessionUseCase;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.domain.repository.SessionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 상태 전이가 동시에 들어와도 생명주기가 역행하지 않아야 한다.
 *
 * <p>잠금이 없으면 시작과 종료가 같은 상태를 읽고 각자 전이한다. 늦게 커밋한 시작이 종료를 덮어쓰면 <b>끝난 수업이 다시 LIVE 로 되살아난다.</b> 동시 시작 두 건이 모두
 * {@code started=true} 가 되어 시작 시각·이력을 중복 기록하는 것도 같은 원인이다.
 *
 * <p>실제 행 잠금을 검증하므로 로컬 MySQL·Redis 가 떠 있어야 한다. 세션 저장이 {@code REQUIRES_NEW} 라 테스트 트랜잭션으로 롤백되지 않아 정리를 직접 한다.
 */
@SpringBootTest
class StartEndConcurrencyTest {

    private static final long INSTRUCTOR_ID = 9_700_000L;
    private static final int CONCURRENT_REQUESTS = 8;

    @Autowired
    private StartSessionUseCase startSessionUseCase;

    @Autowired
    private EndSessionByInstructorUseCase endSessionByInstructorUseCase;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private Long sessionId;

    @BeforeEach
    void seedMember() {
        jdbcTemplate.update(
                "INSERT IGNORE INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                INSTRUCTOR_ID,
                "start-end-concurrency-" + INSTRUCTOR_ID,
                INSTRUCTOR_ID + "@zani.local",
                "동시 전이 테스트 강사");
        redisTemplate.delete("session:active-lock:" + INSTRUCTOR_ID);
    }

    @AfterEach
    void tearDown() {
        if (sessionId != null) {
            jdbcTemplate.update("DELETE FROM session_participants WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM session_status_changes WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", sessionId);
        }
        jdbcTemplate.update("DELETE FROM members WHERE id = ?", INSTRUCTOR_ID);
        redisTemplate.delete("session:active-lock:" + INSTRUCTOR_ID);
    }

    /** 시작이 늦게 커밋돼도 이미 끝난 세션을 되살리면 안 된다. */
    @Test
    void aLateStartDoesNotResurrectAnEndedSession() throws InterruptedException {
        prepareSession();

        AtomicInteger started = new AtomicInteger();
        AtomicInteger ended = new AtomicInteger();
        runConcurrently(index -> {
            if (index % 2 == 0) {
                if (startSessionUseCase
                        .start(new StartSessionCommand(sessionId, INSTRUCTOR_ID))
                        .started()) {
                    started.incrementAndGet();
                }
            } else if (endSessionByInstructorUseCase
                    .endByInstructor(new EndSessionByInstructorCommand(sessionId, INSTRUCTOR_ID))
                    .ended()) {
                ended.incrementAndGet();
            }
        });

        // 어느 쪽이 먼저 이겼는지는 경쟁 결과라 고정하지 않는다. 고정해야 하는 건 "되살아나지 않는다" 다.
        SessionStatus finalStatus = statusOf(sessionId);
        if (ended.get() > 0) {
            assertNotEquals(SessionStatus.LIVE, finalStatus, "종료된 세션이 시작으로 되살아났다");
        }
        // 종료는 한 번만 일어난다(나머지는 멱등하게 false).
        assertEquals(ended.get() > 0 ? 1 : 0, ended.get());
    }

    /** 동시 시작은 한 번만 성공해야 한다. 둘 다 성공하면 시작 시각과 전이 이력이 중복된다. */
    @Test
    void onlyOneConcurrentStartTakesEffect() throws InterruptedException {
        prepareSession();

        AtomicInteger started = new AtomicInteger();
        runConcurrently(index -> {
            if (startSessionUseCase
                    .start(new StartSessionCommand(sessionId, INSTRUCTOR_ID))
                    .started()) {
                started.incrementAndGet();
            }
        });

        assertEquals(1, started.get());
        assertEquals(SessionStatus.LIVE, statusOf(sessionId));
        Long startTransitions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_status_changes"
                        + " WHERE session_id = ? AND from_status = 'PREPARING' AND to_status = 'LIVE'",
                Long.class,
                sessionId);
        assertEquals(1L, startTransitions);
    }

    private void prepareSession() {
        Session prepared = Session.prepare(TsidGenerator.generate(), INSTRUCTOR_ID, "동시 전이 테스트", inviteCode());
        sessionId = sessionRepository.save(prepared).id();
    }

    /** 다른 테스트와 코드가 겹치지 않게 세션 ID 로 만든다(초대 코드는 UNIQUE). */
    private static String inviteCode() {
        return String.format("CC%06d", (int) (System.nanoTime() % 1_000_000));
    }

    private SessionStatus statusOf(long id) {
        return SessionStatus.valueOf(
                jdbcTemplate.queryForObject("SELECT status FROM sessions WHERE id = ?", String.class, id));
    }

    private void runConcurrently(IndexedTask task) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_REQUESTS);

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            int index = i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.run(index);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException expected) {
                    // 진 쪽은 업무 예외(이미 종료됨 등)를 받는다. 여기서 세는 건 성공 횟수뿐이다.
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await(20, TimeUnit.SECONDS);
        executor.shutdown();
    }

    private interface IndexedTask {
        void run(int index);
    }
}
