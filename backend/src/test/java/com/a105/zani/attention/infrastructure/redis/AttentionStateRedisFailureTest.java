package com.a105.zani.attention.infrastructure.redis;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.a105.zani.attention.application.exception.AttentionStateErrorCode;
import com.a105.zani.attention.application.exception.AttentionStateUnavailableException;
import com.a105.zani.attention.application.port.AttentionSnapshot;
import com.a105.zani.attention.domain.model.AttentionState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Redis에 닿지 못할 때 어댑터가 애플리케이션 에러코드로 바꿔 던지는지 검증한다. 그래야 컨트롤러가 503으로 응답해 코칭만 멈추고 수업은 이어진다.
 *
 * <p>실행 중인 Redis가 아니라 <b>비어 있는 포트</b>를 가리켜 연결 실패를 만든다. 모킹 프레임워크 없이 실제 실패 경로를 그대로 태울 수 있다.
 */
class AttentionStateRedisFailureTest {

    /** 아무도 듣지 않는 포트. 연결이 즉시 실패한다. */
    private static final int UNUSED_PORT = 1;

    private static final long SESSION_ID = 1L;
    private static final long PARTICIPANT_ID = 2L;

    private static LettuceConnectionFactory connectionFactory;
    private static AttentionStateRedisAdapter adapter;

    @BeforeAll
    static void pointAtAnUnreachableRedis() {
        connectionFactory = new LettuceConnectionFactory("localhost", UNUSED_PORT);
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        adapter = new AttentionStateRedisAdapter(redisTemplate);
    }

    @AfterAll
    static void closeConnectionFactory() {
        connectionFactory.destroy();
    }

    @Test
    void reportsTheStoreAsUnavailableWhenTheEventMarkerCannotBeWritten() {
        AttentionStateUnavailableException exception = assertThrows(
                AttentionStateUnavailableException.class,
                () -> adapter.registerEvent(SESSION_ID, PARTICIPANT_ID, "failure-1", Duration.ofMinutes(10)));

        assertEquals(AttentionStateErrorCode.ATTENTION_STORE_UNAVAILABLE, exception.errorCode());
    }

    @Test
    void reportsTheStoreAsUnavailableWhenTheEventMarkerCannotBeCleared() {
        assertThrows(
                AttentionStateUnavailableException.class,
                () -> adapter.clearEvent(SESSION_ID, PARTICIPANT_ID, "failure-1"));
    }

    @Test
    void reportsTheStoreAsUnavailableWhenTheCurrentStateCannotBeWritten() {
        AttentionSnapshot snapshot = new AttentionSnapshot(AttentionState.GOOD, 0.9d, Instant.EPOCH);

        assertThrows(
                AttentionStateUnavailableException.class,
                () -> adapter.recordCurrentState(SESSION_ID, PARTICIPANT_ID, snapshot, Duration.ofSeconds(30)));
    }

    @Test
    void reportsTheStoreAsUnavailableWhenTheTriggerWindowCannotBeMarked() {
        assertThrows(
                AttentionStateUnavailableException.class,
                () -> adapter.markSignificant(
                        SESSION_ID, PARTICIPANT_ID, AttentionState.CONFUSED, Duration.ofMinutes(5)));
    }

    @Test
    void reportsTheStoreAsUnavailableWhenTheDenominatorCannotBeChanged() {
        assertThrows(
                AttentionStateUnavailableException.class,
                () -> adapter.excludeFromDenominator(SESSION_ID, PARTICIPANT_ID, Duration.ofHours(3)));
        assertThrows(
                AttentionStateUnavailableException.class,
                () -> adapter.includeInDenominator(SESSION_ID, PARTICIPANT_ID));
    }
}
