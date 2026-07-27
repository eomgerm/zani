package com.a105.zani.session.application.create;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.a105.zani.session.application.exception.ActiveSessionExistsException;
import com.a105.zani.session.domain.InviteCodeGenerator;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.repository.SessionRepository;
import com.a105.zani.session.infrastructure.redis.SessionActivationLockRedisAdapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 같은 강사가 동시에 여러 번 세션 생성을 요청해도 오직 하나만 LIVE로 성공해야 한다. Redis 기반 활성화 락(SessionActivationLockRedisAdapter)의 실제 동시성 보장을 검증하므로
 * 로컬 또는 CI Redis가 떠 있어야 통과한다. Redis 주소는 애플리케이션 로컬 프로필과 동일하게 {@code LOCAL_REDIS_HOST}, {@code LOCAL_REDIS_PORT} 환경 변수를
 * 사용한다.
 */
class CreateSessionConcurrencyTest {

    private static final long INSTRUCTOR_ID = 12345L;
    private static final int CONCURRENT_REQUESTS = 10;

    private LettuceConnectionFactory connectionFactory;
    private CreateSessionService createSessionService;

    @BeforeEach
    void setUp() {
        String redisHost = System.getenv().getOrDefault("LOCAL_REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("LOCAL_REDIS_PORT", "6379"));
        connectionFactory = new LettuceConnectionFactory(redisHost, redisPort);
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        SessionActivationLockRedisAdapter lockPort = new SessionActivationLockRedisAdapter(redisTemplate);
        SessionRepository sessionRepository = new InMemorySessionRepository();
        createSessionService = new CreateSessionService(
                new NewSessionSaver(sessionRepository), lockPort, new InviteCodeGenerator(), event -> {});

        redisTemplate.delete("session:active-lock:" + INSTRUCTOR_ID);
    }

    @AfterEach
    void tearDown() {
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.delete("session:active-lock:" + INSTRUCTOR_ID);
        connectionFactory.destroy();
    }

    @Test
    void onlyOneConcurrentCreateSucceedsForTheSameInstructor() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch readyLatch = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_REQUESTS);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    createSessionService.create(new CreateSessionCommand(INSTRUCTOR_ID, "동시성 테스트 세션"));
                    successCount.incrementAndGet();
                } catch (ActiveSessionExistsException expected) {
                    conflictCount.incrementAndGet();
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

        assertEquals(1, successCount.get());
        assertEquals(CONCURRENT_REQUESTS - 1, conflictCount.get());
    }

    private static class InMemorySessionRepository implements SessionRepository {

        private final Map<Long, Session> store = new ConcurrentHashMap<>();

        @Override
        public Session save(Session session) {
            store.put(session.id(), session);
            return session;
        }

        @Override
        public java.util.Optional<Session> findById(Long id) {
            return java.util.Optional.ofNullable(store.get(id));
        }

        @Override
        public java.util.List<Session> findLiveStartedBefore(java.time.Instant startedBefore, int limit) {
            return java.util.List.of();
        }

        @Override
        public java.util.Optional<Session> findByInviteCode(String inviteCode) {
            return store.values().stream()
                    .filter(session -> session.inviteCode().equals(inviteCode))
                    .findFirst();
        }
    }
}
