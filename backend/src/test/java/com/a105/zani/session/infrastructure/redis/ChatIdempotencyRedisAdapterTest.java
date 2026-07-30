package com.a105.zani.session.infrastructure.redis;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.a105.zani.session.application.port.ChatIdempotencyPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 채팅 재시도 멱등 기록의 동작 고정. 실제 Redis 로 확인한다.
 *
 * <p>선점과 기존 값 조회를 한 번의 왕복(Lua)으로 처리하는 것이 핵심이다. 두 명령으로 나누면 재시도임을 확정한 뒤 조회만 실패했을 때 "처음 보는 값"으로 내려가, 이미 저장된 메시지가 한 번 더
 * 저장·브로드캐스트된다.
 */
@SpringBootTest
class ChatIdempotencyRedisAdapterTest {

    private static final long SESSION_ID = 9_500_910L;

    @Autowired
    private ChatIdempotencyPort chatIdempotencyPort;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private void clearKeys() {
        Set<String> keys = redisTemplate.keys("session:" + SESSION_ID + ":chat:sent:*");
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
    void 처음_보는_전송은_비어_있는_값을_준다() {
        assertTrue(chatIdempotencyPort.claim(SESSION_ID, "c-1", "5001").isEmpty());
    }

    @Test
    void 재시도는_처음_부여한_eventId를_그대로_준다() {
        chatIdempotencyPort.claim(SESSION_ID, "c-1", "5001");

        Optional<String> retry = chatIdempotencyPort.claim(SESSION_ID, "c-1", "5002");

        assertEquals("5001", retry.orElse(null));
    }

    /** 선점이 늦은 쪽을 덮어쓰면 두 번째 전송이 새 전송으로 처리돼 같은 메시지가 두 번 저장된다. */
    @Test
    void 나중_전송이_먼저_부여된_eventId를_덮어쓰지_않는다() {
        chatIdempotencyPort.claim(SESSION_ID, "c-1", "5001");
        chatIdempotencyPort.claim(SESSION_ID, "c-1", "5002");

        assertEquals(
                "5001", chatIdempotencyPort.claim(SESSION_ID, "c-1", "5003").orElse(null));
    }

    @Test
    void clientEventId가_다르면_서로_영향을_주지_않는다() {
        assertTrue(chatIdempotencyPort.claim(SESSION_ID, "c-1", "5001").isEmpty());
        assertTrue(chatIdempotencyPort.claim(SESSION_ID, "c-2", "5002").isEmpty());

        assertEquals(
                "5001", chatIdempotencyPort.claim(SESSION_ID, "c-1", "9999").orElse(null));
        assertEquals(
                "5002", chatIdempotencyPort.claim(SESSION_ID, "c-2", "9999").orElse(null));
    }

    /** 선점만 남고 행이 없을 때 쓴다. 지우기만 하면 이어지는 저장이 멱등 보호를 못 받는다. */
    @Test
    void 다시_잡으면_선점이_새_eventId를_가리킨다() {
        chatIdempotencyPort.claim(SESSION_ID, "c-1", "5001");

        chatIdempotencyPort.reclaim(SESSION_ID, "c-1", "5002");

        assertEquals(
                "5002", chatIdempotencyPort.claim(SESSION_ID, "c-1", "9999").orElse(null));
    }

    /** NX 가 아니라 덮어쓰기여야 한다. 조건이 붙으면 이미 있는 선점을 바꾸지 못한다. */
    @Test
    void 다시_잡기는_선점이_없어도_새로_만든다() {
        chatIdempotencyPort.reclaim(SESSION_ID, "c-2", "5003");

        assertEquals(
                "5003", chatIdempotencyPort.claim(SESSION_ID, "c-2", "9999").orElse(null));
    }

    /** 되돌리지 않으면 저장에 실패한 전송의 재시도가 영구히 중복으로 걸러져 메시지가 사라진다. */
    @Test
    void 되돌리면_같은_clientEventId가_다시_처음_보는_값이_된다() {
        chatIdempotencyPort.claim(SESSION_ID, "c-1", "5001");

        chatIdempotencyPort.release(SESSION_ID, "c-1");

        assertTrue(chatIdempotencyPort.claim(SESSION_ID, "c-1", "5002").isEmpty());
    }

    /** TTL 이 없으면 키가 영원히 쌓인다. */
    @Test
    void 선점에_만료_시간을_건다() {
        chatIdempotencyPort.claim(SESSION_ID, "c-1", "5001");

        Long ttlSeconds = redisTemplate.getExpire("session:" + SESSION_ID + ":chat:sent:c-1");

        assertNotNull(ttlSeconds);
        assertTrue(ttlSeconds > 0, "만료 시간이 걸려 있지 않다: " + ttlSeconds);
    }
}
