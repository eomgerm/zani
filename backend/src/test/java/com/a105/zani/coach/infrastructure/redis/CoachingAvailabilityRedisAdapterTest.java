package com.a105.zani.coach.infrastructure.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * coach:available:{sessionId} 저장/조회를 실제 Redis 로 검증한다. 로컬/CI Redis 가 떠 있어야 통과하며, 주소는 {@code LOCAL_REDIS_HOST},
 * {@code LOCAL_REDIS_PORT} 환경 변수를 사용한다(없으면 localhost:6379).
 */
class CoachingAvailabilityRedisAdapterTest {

    private static final long SESSION_ID = -900001L;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private CoachingAvailabilityRedisAdapter adapter;

    @BeforeEach
    void setUp() {
        String host = System.getenv().getOrDefault("LOCAL_REDIS_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("LOCAL_REDIS_PORT", "6379"));
        connectionFactory = new LettuceConnectionFactory(host, port);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        adapter = new CoachingAvailabilityRedisAdapter(redisTemplate);
        redisTemplate.delete("coach:available:" + SESSION_ID);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete("coach:available:" + SESSION_ID);
        connectionFactory.destroy();
    }

    @Test
    void storesAndReadsAvailability() {
        adapter.store(SESSION_ID, true);
        assertEquals(Boolean.TRUE, adapter.find(SESSION_ID).orElse(null));

        adapter.store(SESSION_ID, false);
        assertEquals(Boolean.FALSE, adapter.find(SESSION_ID).orElse(null));
    }

    @Test
    void findReturnsEmptyWhenAbsent() {
        assertTrue(adapter.find(SESSION_ID).isEmpty());
    }
}
