package com.a105.zani.session.application.joinsession;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.a105.zani.common.infrastructure.persistence.TsidGenerator;
import com.a105.zani.session.domain.InviteCodeGenerator;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.repository.SessionRepository;
import com.a105.zani.session.infrastructure.persistence.repository.SessionJpaRepository;
import com.a105.zani.session.infrastructure.persistence.repository.SessionMemberJpaRepository;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 같은 학생이 같은 초대 코드로 동시에 여러 번 입장 요청을 보내도(멱등 가입),
 * session_members에는 정확히 1행만 남아야 한다. 로컬 MySQL이 떠 있어야 통과한다.
 */
@SpringBootTest
class SessionJoinConcurrencyTest {

    private static final long INSTRUCTOR_ID = -1L;
    private static final long STUDENT_ID = -2L;
    private static final int CONCURRENT_REQUESTS = 10;

    @Autowired
    private JoinSessionUseCase joinSessionUseCase;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private SessionMemberJpaRepository sessionMemberJpaRepository;

    @Autowired
    private SessionJpaRepository sessionJpaRepository;

    private Long sessionId;

    @AfterEach
    void tearDown() {
        if (sessionId != null) {
            sessionMemberJpaRepository.findBySessionIdAndUserId(sessionId, STUDENT_ID)
                    .ifPresent(sessionMemberJpaRepository::delete);
            sessionJpaRepository.deleteById(sessionId);
        }
    }

    @Test
    void concurrentJoinsForTheSameStudentResultInExactlyOneMembership() throws InterruptedException {
        String inviteCode = new InviteCodeGenerator().generate();
        Session session = sessionRepository.save(
                Session.start(TsidGenerator.generate(), INSTRUCTOR_ID, "동시 입장 테스트", inviteCode, Instant.now()));
        sessionId = session.id();

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch readyLatch = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_REQUESTS);
        AtomicInteger successCount = new AtomicInteger();

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    joinSessionUseCase.join(new JoinSessionCommand(inviteCode, STUDENT_ID));
                    successCount.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(CONCURRENT_REQUESTS, successCount.get());
        long memberRowCount = sessionMemberJpaRepository.findBySessionIdAndUserId(sessionId, STUDENT_ID)
                .stream()
                .count();
        assertEquals(1, memberRowCount);
    }
}
