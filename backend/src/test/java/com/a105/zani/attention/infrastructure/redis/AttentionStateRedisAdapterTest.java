package com.a105.zani.attention.infrastructure.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.a105.zani.attention.application.port.AttentionSnapshot;
import com.a105.zani.attention.domain.model.AttentionState;
import com.a105.zani.attention.domain.model.DetectionRunCounters;
import com.a105.zani.attention.domain.model.DetectionRunTransition;
import com.a105.zani.attention.domain.model.RunStep;

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
    private static final String LOW_RUN_KEY = "attention:" + SESSION_ID + ":run:low:" + PARTICIPANT_ID;
    private static final String UNMEASURABLE_RUN_KEY =
            "attention:" + SESSION_ID + ":run:unmeasurable:" + PARTICIPANT_ID;
    private static final String APPLIED_KEY = "attention:" + SESSION_ID + ":applied:" + PARTICIPANT_ID;
    private static final String OUTAGE_KEY = "attention:" + SESSION_ID + ":outage:" + PARTICIPANT_ID;
    private static final Duration RUN_TTL = Duration.ofMinutes(2);
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
        redisTemplate.delete(LOW_RUN_KEY);
        redisTemplate.delete(UNMEASURABLE_RUN_KEY);
        redisTemplate.delete(APPLIED_KEY);
        redisTemplate.delete(OUTAGE_KEY);
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

    @Test
    void countsUpAndArmsTheTtlInOneStep() {
        DetectionRunCounters first = apply(new DetectionRunTransition(RunStep.INCREMENT, RunStep.RESET), 10_000L);
        DetectionRunCounters second = apply(new DetectionRunTransition(RunStep.INCREMENT, RunStep.RESET), 20_000L);

        assertEquals(1, first.lowEngagement());
        assertEquals(2, second.lowEngagement());
        Long ttl = redisTemplate.getExpire(LOW_RUN_KEY);
        assertNotNull(ttl);
        // INCR 과 EXPIRE 가 갈라지면 TTL 없는 카운터가 남아 재입장 학생이 옛 값을 물려받는다.
        assertTrue(ttl > 0 && ttl <= 120, "TTL should be armed, was " + ttl);
    }

    @Test
    void keepsACounterWithoutChangingItAndStillRefreshesItsTtl() {
        apply(new DetectionRunTransition(RunStep.INCREMENT, RunStep.RESET), 10_000L);

        DetectionRunCounters kept = apply(new DetectionRunTransition(RunStep.KEEP, RunStep.INCREMENT), 20_000L);

        assertEquals(1, kept.lowEngagement());
        assertEquals(1, kept.unmeasurable());
        Long ttl = redisTemplate.getExpire(LOW_RUN_KEY);
        assertNotNull(ttl);
        assertTrue(ttl > 0, "kept counter should keep its TTL armed, was " + ttl);
    }

    @Test
    void clearsACounterOnReset() {
        apply(new DetectionRunTransition(RunStep.INCREMENT, RunStep.INCREMENT), 10_000L);

        DetectionRunCounters reset = apply(new DetectionRunTransition(RunStep.RESET, RunStep.RESET), 20_000L);

        assertEquals(0, reset.lowEngagement());
        assertEquals(0, reset.unmeasurable());
        assertNull(redisTemplate.opsForValue().get(LOW_RUN_KEY));
    }

    @Test
    void clearsBothCountersWhenAPromptCloses() {
        apply(new DetectionRunTransition(RunStep.INCREMENT, RunStep.INCREMENT), 10_000L);

        adapter.resetRuns(SESSION_ID, PARTICIPANT_ID);

        assertNull(redisTemplate.opsForValue().get(LOW_RUN_KEY));
        assertNull(redisTemplate.opsForValue().get(UNMEASURABLE_RUN_KEY));
    }

    @Test
    void remembersTheLastAppliedJudgementOffsetInTheSameStepAsTheCounters() {
        assertNull(redisTemplate.opsForValue().get(APPLIED_KEY));

        apply(new DetectionRunTransition(RunStep.INCREMENT, RunStep.RESET), 70_000L);

        // 카운터만 오르고 반영 지점이 빠지면, 재시도가 그 사실을 몰라 같은 관측으로 카운터를 한 번 더 올린다.
        assertEquals("70000", redisTemplate.opsForValue().get(APPLIED_KEY));
        Long ttl = redisTemplate.getExpire(APPLIED_KEY);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 120, "TTL should be armed, was " + ttl);
    }

    @Test
    void refusesAnObservationThatANewerOneHasAlreadyOvertaken() {
        apply(new DetectionRunTransition(RunStep.INCREMENT, RunStep.RESET), 30_000L);

        Optional<DetectionRunCounters> late = adapter.applyObservation(
                SESSION_ID,
                PARTICIPANT_ID,
                new DetectionRunTransition(RunStep.INCREMENT, RunStep.RESET),
                20_000L,
                RUN_TTL);

        // 판단을 밖에서 하면 겹쳐 들어온 두 판정이 둘 다 "내가 최신"으로 읽고, 옛 쪽이 나중에 써서 반영 지점을 되돌린다.
        assertTrue(late.isEmpty());
        assertEquals("1", redisTemplate.opsForValue().get(LOW_RUN_KEY));
        assertEquals("30000", redisTemplate.opsForValue().get(APPLIED_KEY));
    }

    @Test
    void refusesAResendOfTheJudgementItJustApplied() {
        apply(new DetectionRunTransition(RunStep.INCREMENT, RunStep.RESET), 30_000L);

        Optional<DetectionRunCounters> resent = adapter.applyObservation(
                SESSION_ID,
                PARTICIPANT_ID,
                new DetectionRunTransition(RunStep.INCREMENT, RunStep.RESET),
                30_000L,
                RUN_TTL);

        assertTrue(resent.isEmpty());
        assertEquals("1", redisTemplate.opsForValue().get(LOW_RUN_KEY));
    }

    @Test
    void keepsTheAppliedOffsetWhenAPromptClearsTheCounters() {
        apply(new DetectionRunTransition(RunStep.INCREMENT, RunStep.INCREMENT), 30_000L);

        adapter.resetRuns(SESSION_ID, PARTICIPANT_ID);

        // 프롬프트에 답했다고 어디까지 반영했는지를 잊으면, 뒤늦게 도착한 옛 관측이 다시 반영된다.
        assertEquals("30000", redisTemplate.opsForValue().get(APPLIED_KEY));
    }

    @Test
    void startsTheOutageClockOnTheFirstUnmeasurableObservation() {
        OptionalLong outage = adapter.trackMeasurementOutage(SESSION_ID, PARTICIPANT_ID, true, 30_000L, RUN_TTL);

        assertEquals(0L, outage.getAsLong());
        assertEquals("30000", redisTemplate.opsForValue().get(OUTAGE_KEY));
        Long ttl = redisTemplate.getExpire(OUTAGE_KEY);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 120, "TTL should be armed, was " + ttl);
    }

    @Test
    void measuresTheOutageFromWhereItStartedNotFromTheLastObservation() {
        adapter.trackMeasurementOutage(SESSION_ID, PARTICIPANT_ID, true, 30_000L, RUN_TTL);
        adapter.trackMeasurementOutage(SESSION_ID, PARTICIPANT_ID, true, 60_000L, RUN_TTL);

        OptionalLong outage = adapter.trackMeasurementOutage(SESSION_ID, PARTICIPANT_ID, true, 90_000L, RUN_TTL);

        // 시작 지점을 매번 새로 심으면 구간이 1분을 넘길 수 없어 아무도 분모에서 빠지지 않는다.
        assertEquals(60_000L, outage.getAsLong());
        assertEquals("30000", redisTemplate.opsForValue().get(OUTAGE_KEY));
    }

    @Test
    void endsTheOutageTheMomentMeasurementBecomesPossibleAgain() {
        adapter.trackMeasurementOutage(SESSION_ID, PARTICIPANT_ID, true, 30_000L, RUN_TTL);

        OptionalLong outage = adapter.trackMeasurementOutage(SESSION_ID, PARTICIPANT_ID, false, 40_000L, RUN_TTL);

        assertTrue(outage.isEmpty());
        assertNull(redisTemplate.opsForValue().get(OUTAGE_KEY));
    }

    private DetectionRunCounters apply(DetectionRunTransition transition, long observedOffsetMs) {
        return adapter.applyObservation(SESSION_ID, PARTICIPANT_ID, transition, observedOffsetMs, RUN_TTL)
                .orElseThrow();
    }
}
