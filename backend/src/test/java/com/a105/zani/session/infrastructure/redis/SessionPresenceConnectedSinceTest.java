package com.a105.zani.session.infrastructure.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** presence 값이 연속 접속 시작 시각을 들고 있는지 검증한다. 로컬 Redis가 떠 있어야 통과한다. */
@SpringBootTest
class SessionPresenceConnectedSinceTest {

    private static final long SESSION_ID = 9_200_910L;
    private static final long STUDENT_A = 910L;
    private static final long STUDENT_B = 911L;
    private static final Duration PRESENCE_TTL = Duration.ofSeconds(30);
    private static final Instant FIRST = Instant.parse("2026-07-29T09:00:00Z");

    @Autowired
    private SessionPresenceRedisAdapter adapter;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void clearKeys() {
        redisTemplate.delete(List.of(presenceKey(STUDENT_A), presenceKey(STUDENT_B)));
    }

    private static String presenceKey(long participantId) {
        return "session:" + SESSION_ID + ":presence:" + participantId;
    }

    @Test
    void keepsTheFirstConnectionInstantAcrossLaterHeartbeats() {
        Instant first = adapter.recordHeartbeat(SESSION_ID, STUDENT_A, FIRST, PRESENCE_TTL);
        Instant afterTenSeconds = adapter.recordHeartbeat(SESSION_ID, STUDENT_A, FIRST.plusSeconds(10), PRESENCE_TTL);
        Instant afterTwentySeconds =
                adapter.recordHeartbeat(SESSION_ID, STUDENT_A, FIRST.plusSeconds(20), PRESENCE_TTL);

        // 매 heartbeat 가 시작 시각을 밀면 연속 접속 1분이 영원히 채워지지 않아 아무도 분모에 들어오지 못한다.
        assertEquals(FIRST, first);
        assertEquals(FIRST, afterTenSeconds);
        assertEquals(FIRST, afterTwentySeconds);
    }

    @Test
    void armsTheTtlOnEveryHeartbeat() {
        adapter.recordHeartbeat(SESSION_ID, STUDENT_A, FIRST, PRESENCE_TTL);

        Long ttl = redisTemplate.getExpire(presenceKey(STUDENT_A));
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 30, "TTL should be armed, was " + ttl);
    }

    @Test
    void startsANewConnectionAfterTheStudentDisconnects() {
        adapter.recordHeartbeat(SESSION_ID, STUDENT_A, FIRST, PRESENCE_TTL);
        adapter.clearPresence(SESSION_ID, STUDENT_A);

        Instant reconnected = adapter.recordHeartbeat(SESSION_ID, STUDENT_A, FIRST.plusSeconds(90), PRESENCE_TTL);

        // "연속" 접속이므로 끊긴 뒤에는 처음부터 다시 센다. 이어서 세면 잠깐 붙었다 끊는 학생이 계속 분모에 남는다.
        assertEquals(FIRST.plusSeconds(90), reconnected);
    }

    @Test
    void reportsOnlyTheParticipantsThatStillHaveAPresenceKey() {
        adapter.recordHeartbeat(SESSION_ID, STUDENT_A, FIRST, PRESENCE_TTL);

        Map<Long, Instant> connected = adapter.connectedSince(SESSION_ID, List.of(STUDENT_A, STUDENT_B));

        assertEquals(Map.of(STUDENT_A, FIRST), connected);
    }

    @Test
    void treatsAValueWithoutAConnectionInstantAsAFreshConnection() {
        // 시작 시각을 담기 전에 심긴 키(값이 "1")를 만나도 죽지 않고 지금을 시작으로 다시 심는다.
        redisTemplate.opsForValue().set(presenceKey(STUDENT_A), "1", PRESENCE_TTL);

        Instant startedAt = adapter.recordHeartbeat(SESSION_ID, STUDENT_A, FIRST, PRESENCE_TTL);

        assertEquals(FIRST, startedAt);
        assertEquals(Map.of(STUDENT_A, FIRST), adapter.connectedSince(SESSION_ID, List.of(STUDENT_A)));
    }

    @Test
    void asksNothingWhenThereAreNoCandidates() {
        assertEquals(Map.of(), adapter.connectedSince(SESSION_ID, List.of()));
    }
}
