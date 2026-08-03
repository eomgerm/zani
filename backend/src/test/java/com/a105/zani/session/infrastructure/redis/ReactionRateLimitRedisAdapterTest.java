package com.a105.zani.session.infrastructure.redis;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.a105.zani.session.application.port.ReactionRateLimitPort;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 반응 연타 제한의 동작 고정. 실제 Redis 로 확인한다.
 *
 * <p>{@code SET NX PX} 한 번으로 판정하는 것이 핵심이다. 존재 확인과 기록을 두 명령으로 나누면 그 사이에 낀 두 번째 요청이 함께 통과해, 여러 서버 인스턴스가 붙었을 때 제한이 사실상
 * 풀린다.
 */
@SpringBootTest
class ReactionRateLimitRedisAdapterTest {

    private static final long SESSION_ID = 9_700_910L;

    private static final String IDENTITY = "p-11";
    private static final String OTHER_IDENTITY = "p-22";

    @Autowired
    private ReactionRateLimitPort reactionRateLimitPort;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String cooldownKey(String identity) {
        return "session:" + SESSION_ID + ":reaction:cooldown:" + identity;
    }

    private void clearKeys() {
        Set<String> keys = redisTemplate.keys("session:" + SESSION_ID + ":reaction:cooldown:*");
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @BeforeEach
    void setUp() {
        clearKeys();
    }

    @AfterEach
    void cleanUp() {
        clearKeys();
    }

    @Test
    void 첫_반응은_통과한다() {
        assertTrue(reactionRateLimitPort.tryAcquire(SESSION_ID, IDENTITY));
    }

    @Test
    void 간격이_차기_전의_연타는_막는다() {
        reactionRateLimitPort.tryAcquire(SESSION_ID, IDENTITY);

        assertFalse(reactionRateLimitPort.tryAcquire(SESSION_ID, IDENTITY));
    }

    /** 한 사람의 연타가 남의 반응까지 막으면 수업이 조용해진다. */
    @Test
    void 제한은_참가자별로_따로_센다() {
        reactionRateLimitPort.tryAcquire(SESSION_ID, IDENTITY);

        assertTrue(reactionRateLimitPort.tryAcquire(SESSION_ID, OTHER_IDENTITY));
    }

    /** 만료가 없으면 한 번 보낸 사람이 수업 내내 다시 못 보낸다. */
    @Test
    void 제한_키에_만료가_걸린다() {
        reactionRateLimitPort.tryAcquire(SESSION_ID, IDENTITY);

        Long ttlMillis = redisTemplate.getExpire(cooldownKey(IDENTITY), TimeUnit.MILLISECONDS);

        assertNotNull(ttlMillis);
        assertTrue(ttlMillis > 0, "만료가 설정되지 않았습니다: " + ttlMillis);
    }
}
