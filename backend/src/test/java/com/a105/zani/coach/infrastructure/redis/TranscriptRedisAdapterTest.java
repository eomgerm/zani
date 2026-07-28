package com.a105.zani.coach.infrastructure.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * coach:transcript:{sessionId} 저장·조회를 실제 Redis 로 검증한다. 로컬/CI Redis 가 떠 있어야 통과하며, 주소는 {@code LOCAL_REDIS_HOST},
 * {@code LOCAL_REDIS_PORT} 환경 변수를 사용한다(없으면 localhost:6379).
 */
class TranscriptRedisAdapterTest {

    private static final long SESSION_ID = -900101L;
    private static final String KEY = "coach:transcript:" + SESSION_ID;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private TranscriptRedisAdapter adapter;

    @BeforeEach
    void setUp() {
        String host = System.getenv().getOrDefault("LOCAL_REDIS_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("LOCAL_REDIS_PORT", "6379"));
        connectionFactory = new LettuceConnectionFactory(host, port);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        adapter = new TranscriptRedisAdapter(redisTemplate);
        redisTemplate.delete(KEY);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(KEY);
        connectionFactory.destroy();
    }

    @Test
    void storesAndReadsTranscript() {
        adapter.store(SESSION_ID, "제네릭을 예시로 설명했습니다.");

        assertEquals("제네릭을 예시로 설명했습니다.", adapter.find(SESSION_ID).orElse(null));
    }

    @Test
    void overwritesPreviousTranscript() {
        adapter.store(SESSION_ID, "이전 구간");
        adapter.store(SESSION_ID, "최신 구간");

        assertEquals("최신 구간", adapter.find(SESSION_ID).orElse(null));
    }

    @Test
    void setsExpiration() {
        adapter.store(SESSION_ID, "만료 확인");

        Long ttlSeconds = redisTemplate.getExpire(KEY);
        assertTrue(ttlSeconds != null && ttlSeconds > 0 && ttlSeconds <= 600, "TTL 이 10분 이내로 설정돼야 한다: " + ttlSeconds);
    }

    @Test
    void findReturnsEmptyWhenAbsent() {
        assertTrue(adapter.find(SESSION_ID).isEmpty());
    }
}
