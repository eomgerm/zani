package com.a105.zani.session.application.join;

import java.time.Instant;
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
import org.springframework.jdbc.core.JdbcTemplate;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.session.domain.InviteCodeGenerator;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.repository.SessionRepository;
import com.a105.zani.session.infrastructure.persistence.repository.SessionJpaRepository;
import com.a105.zani.session.infrastructure.persistence.repository.SessionParticipantJpaRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 같은 학생이 같은 초대 코드로 동시에 여러 번 입장 요청을 보내도(멱등 가입), session_participants 에는 정확히 1행만 남아야 한다. ERD 기준
 * session_participants.member_id 는 members 를 FK 로 참조하므로 테스트 회원을 먼저 시딩한다. 로컬 MySQL이 떠 있어야 통과한다.
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
    private SessionParticipantJpaRepository sessionParticipantJpaRepository;

    @Autowired
    private SessionJpaRepository sessionJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long sessionId;

    @BeforeEach
    void seedMembers() {
        insertMemberIfAbsent(INSTRUCTOR_ID, "동시성 테스트 강사");
        insertMemberIfAbsent(STUDENT_ID, "동시성 테스트 학생");
    }

    @AfterEach
    void tearDown() {
        if (sessionId != null) {
            jdbcTemplate.update(
                    "DELETE FROM session_participants WHERE session_id = ? AND member_id = ?", sessionId, STUDENT_ID);
            sessionJpaRepository.deleteById(sessionId);
        }
        jdbcTemplate.update("DELETE FROM members WHERE id IN (?, ?)", INSTRUCTOR_ID, STUDENT_ID);
    }

    private void insertMemberIfAbsent(long id, String displayName) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO members (id, google_subject, email, display_name, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                id,
                "concurrency-test-subject-" + id,
                "concurrency-test-" + id + "@zani.local",
                displayName);
    }

    @Test
    void concurrentJoinsForTheSameStudentResultInExactlyOneParticipant() throws InterruptedException {
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
        Long memberRowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_participants WHERE session_id = ? AND member_id = ?",
                Long.class,
                sessionId,
                STUDENT_ID);
        assertEquals(1L, memberRowCount);
    }
}
