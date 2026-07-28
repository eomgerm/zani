package com.a105.zani.attention.infrastructure.redis;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.a105.zani.attention.application.port.AttentionSnapshot;
import com.a105.zani.attention.domain.model.AttentionState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 판정 상태 Redis 어댑터의 키·TTL·멱등 동작 검증. 로컬 Redis가 떠 있어야 통과한다. */
@SpringBootTest
class AttentionStateRedisAdapterTest {

    private static final long SESSION_ID = 9_000_910L;
    private static final long PARTICIPANT_ID = 910L;
    private static final long OTHER_PARTICIPANT_ID = 911L;
    private static final Instant RECORDED_AT = Instant.parse("2026-07-28T09:00:10Z");

    private static final String STATE_KEY = "attention:" + SESSION_ID + ":state:" + PARTICIPANT_ID;
    private static final String EXCLUDED_KEY = "attention:" + SESSION_ID + ":excluded:" + PARTICIPANT_ID;
    private static final String EVENT_KEY = "attention:" + SESSION_ID + ":" + PARTICIPANT_ID + ":event:redis-adapter-1";

    @Autowired
    private AttentionStateRedisAdapter adapter;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static String significantKey(AttentionState state) {
        return "attention:" + SESSION_ID + ":significant:" + state.name() + ":" + PARTICIPANT_ID;
    }

    @AfterEach
    void clearKeys() {
        redisTemplate.delete(STATE_KEY);
        redisTemplate.delete(EVENT_KEY);
        redisTemplate.delete(EXCLUDED_KEY);
        for (AttentionState state : AttentionState.values()) {
            redisTemplate.delete(significantKey(state));
        }
    }

    @Test
    void registersAnEventOnlyTheFirstTimeItIsSeen() {
        assertTrue(adapter.registerEvent(SESSION_ID, PARTICIPANT_ID, "redis-adapter-1", Duration.ofMinutes(10)));

        assertFalse(adapter.registerEvent(SESSION_ID, PARTICIPANT_ID, "redis-adapter-1", Duration.ofMinutes(10)));
    }

    @Test
    void scopesTheIdempotencyKeyToOneParticipant() {
        adapter.registerEvent(SESSION_ID, PARTICIPANT_ID, "redis-adapter-1", Duration.ofMinutes(10));

        // 클라이언트가 창 시작 시각 같은 결정적 값을 쓰면 학생끼리 같은 id 가 된다. 서로를 막으면 안 된다.
        assertTrue(adapter.registerEvent(SESSION_ID, OTHER_PARTICIPANT_ID, "redis-adapter-1", Duration.ofMinutes(10)));
        redisTemplate.delete("attention:" + SESSION_ID + ":" + OTHER_PARTICIPANT_ID + ":event:redis-adapter-1");
    }

    @Test
    void letsAClearedEventBeRegisteredAgain() {
        adapter.registerEvent(SESSION_ID, PARTICIPANT_ID, "redis-adapter-1", Duration.ofMinutes(10));

        adapter.clearEvent(SESSION_ID, PARTICIPANT_ID, "redis-adapter-1");

        assertTrue(adapter.registerEvent(SESSION_ID, PARTICIPANT_ID, "redis-adapter-1", Duration.ofMinutes(10)));
    }

    @Test
    void storesTheStateWithTheSignalQualityTheAggregationNeeds() {
        adapter.recordCurrentState(
                SESSION_ID,
                PARTICIPANT_ID,
                new AttentionSnapshot(AttentionState.CONFUSED, 0.83d, RECORDED_AT),
                Duration.ofSeconds(30));

        assertEquals(
                "CONFUSED|0.83|" + RECORDED_AT.toEpochMilli(),
                redisTemplate.opsForValue().get(STATE_KEY));
    }

    @Test
    void writesTheStateAndItsTtlInOneCommandSoNoKeyCanOutliveTheJudgement() {
        adapter.recordCurrentState(
                SESSION_ID,
                PARTICIPANT_ID,
                new AttentionSnapshot(AttentionState.GOOD, 0.9d, RECORDED_AT),
                Duration.ofSeconds(30));

        Long ttl = redisTemplate.getExpire(STATE_KEY);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 30, "TTL should be armed, was " + ttl);
    }

    @Test
    void refreshesTheStateTtlOnEveryJudgement() {
        adapter.recordCurrentState(
                SESSION_ID,
                PARTICIPANT_ID,
                new AttentionSnapshot(AttentionState.GOOD, 0.9d, RECORDED_AT),
                Duration.ofSeconds(2));

        adapter.recordCurrentState(
                SESSION_ID,
                PARTICIPANT_ID,
                new AttentionSnapshot(AttentionState.MISSED, 0.7d, RECORDED_AT),
                Duration.ofSeconds(30));

        Long ttl = redisTemplate.getExpire(STATE_KEY);
        assertNotNull(ttl);
        assertTrue(ttl > 2, "TTL should have been extended, was " + ttl);
    }

    @Test
    void keepsTheSignificantMarkForTheObservationWindow() {
        adapter.markSignificant(SESSION_ID, PARTICIPANT_ID, AttentionState.CONFUSED, Duration.ofMinutes(5));

        String key = significantKey(AttentionState.CONFUSED);
        assertEquals("1", redisTemplate.opsForValue().get(key));
        Long ttl = redisTemplate.getExpire(key);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 300, "TTL should be armed, was " + ttl);
    }

    @Test
    void countsEachSignificantStateSeparatelyWithinOneWindow() {
        adapter.markSignificant(SESSION_ID, PARTICIPANT_ID, AttentionState.CONFUSED, Duration.ofMinutes(5));
        adapter.markSignificant(SESSION_ID, PARTICIPANT_ID, AttentionState.UNMEASURABLE, Duration.ofMinutes(5));

        // 팁 유형 선택은 유형별 비율을 각각 요구한다. 한 학생이 두 유형을 겪었으면 둘 다 남아야 한다.
        assertEquals("1", redisTemplate.opsForValue().get(significantKey(AttentionState.CONFUSED)));
        assertEquals("1", redisTemplate.opsForValue().get(significantKey(AttentionState.UNMEASURABLE)));
    }

    @Test
    void marksAStudentAsOutOfTheDenominatorWithAnArmedTtl() {
        adapter.excludeFromDenominator(SESSION_ID, PARTICIPANT_ID, Duration.ofHours(3));

        assertEquals("1", redisTemplate.opsForValue().get(EXCLUDED_KEY));
        Long ttl = redisTemplate.getExpire(EXCLUDED_KEY);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 10_800, "TTL should be armed, was " + ttl);
    }

    @Test
    void bringsAStudentBackIntoTheDenominator() {
        adapter.excludeFromDenominator(SESSION_ID, PARTICIPANT_ID, Duration.ofHours(3));

        adapter.includeInDenominator(SESSION_ID, PARTICIPANT_ID);

        assertNull(redisTemplate.opsForValue().get(EXCLUDED_KEY));
    }
}
