package com.a105.zani.attention.infrastructure.redis;

import java.time.Duration;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.attention.application.exception.AttentionStateUnavailableException;
import com.a105.zani.attention.application.port.AttentionSnapshot;
import com.a105.zani.attention.application.port.AttentionStatePort;
import com.a105.zani.attention.domain.model.AttentionState;

/**
 * 참여도 판정 상태를 Redis에 보관한다. 네 종류의 키를 쓰며 모두 TTL로 자연 소멸한다.
 *
 * <ul>
 *   <li>{@code attention:{sessionId}:{participantId}:event:{clientEventId}} — 멱등 판정용 표시(SETNX)
 *   <li>{@code attention:{sessionId}:state:{participantId}} — 현재 판정 상태
 *   <li>{@code attention:{sessionId}:significant:{state}:{participantId}} — 최근 5분 유의 상태 흔적
 *   <li>{@code attention:{sessionId}:excluded:{participantId}} — 집단 비율 분모 제외 표시
 * </ul>
 *
 * <p>현재 상태는 해시가 아니라 문자열 한 개로 쓴다. 해시(HSET)는 TTL을 유지하지 않아 EXPIRE를 따로 걸어야 하는데, 그 사이에 연결이 끊기면 TTL 없는 키가 남아 떠난 학생이 영원히 "측정
 * 중"으로 집계된다. SET은 값과 TTL을 한 번에 처리해 그 틈이 없다.
 *
 * <p>참가자 식별자는 presence와 같은 {@code participantId}를 쓴다. 트리거 분모(presence 키)와 분자(이 키)가 같은 식별자 공간에 있어야 비율을 계산할 수 있다. Redis
 * 장애는 애플리케이션 에러코드로 변환한다.
 */
@Component
@RequiredArgsConstructor
public class AttentionStateRedisAdapter implements AttentionStatePort {

    private static final String MARKER_VALUE = "1";
    /** 현재 상태 값 구분자. {@code 상태|유효프레임비율|기록시각(epoch millis)} 순서로 담는다. */
    private static final String FIELD_SEPARATOR = "|";

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean registerEvent(long sessionId, long participantId, String clientEventId, Duration ttl) {
        try {
            // 처음 보는 이벤트일 때만 표시가 심긴다(SETNX, 원자적). 재시도가 겹쳐도 한 번만 true다.
            return Boolean.TRUE.equals(redisTemplate
                    .opsForValue()
                    .setIfAbsent(eventKey(sessionId, participantId, clientEventId), MARKER_VALUE, ttl));
        } catch (DataAccessException exception) {
            throw new AttentionStateUnavailableException(exception);
        }
    }

    @Override
    public void clearEvent(long sessionId, long participantId, String clientEventId) {
        try {
            redisTemplate.delete(eventKey(sessionId, participantId, clientEventId));
        } catch (DataAccessException exception) {
            throw new AttentionStateUnavailableException(exception);
        }
    }

    @Override
    public void recordCurrentState(long sessionId, long participantId, AttentionSnapshot snapshot, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(stateKey(sessionId, participantId), encode(snapshot), ttl);
        } catch (DataAccessException exception) {
            throw new AttentionStateUnavailableException(exception);
        }
    }

    @Override
    public void markSignificant(long sessionId, long participantId, AttentionState state, Duration window) {
        try {
            // 창 안에 한 번이라도 있었으면 되므로, 매번 TTL을 새로 걸어 마지막 발생 시점부터 창을 센다.
            redisTemplate.opsForValue().set(significantKey(sessionId, participantId, state), MARKER_VALUE, window);
        } catch (DataAccessException exception) {
            throw new AttentionStateUnavailableException(exception);
        }
    }

    private String encode(AttentionSnapshot snapshot) {
        return snapshot.state().name()
                + FIELD_SEPARATOR
                + snapshot.signalQuality()
                + FIELD_SEPARATOR
                + snapshot.recordedAt().toEpochMilli();
    }

    @Override
    public void excludeFromDenominator(long sessionId, long participantId, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(excludedKey(sessionId, participantId), MARKER_VALUE, ttl);
        } catch (DataAccessException exception) {
            throw new AttentionStateUnavailableException(exception);
        }
    }

    @Override
    public void includeInDenominator(long sessionId, long participantId) {
        try {
            redisTemplate.delete(excludedKey(sessionId, participantId));
        } catch (DataAccessException exception) {
            throw new AttentionStateUnavailableException(exception);
        }
    }

    private String eventKey(long sessionId, long participantId, String clientEventId) {
        return "attention:" + sessionId + ":" + participantId + ":event:" + clientEventId;
    }

    private String stateKey(long sessionId, long participantId) {
        return "attention:" + sessionId + ":state:" + participantId;
    }

    private String significantKey(long sessionId, long participantId, AttentionState state) {
        return "attention:" + sessionId + ":significant:" + state.name() + ":" + participantId;
    }

    private String excludedKey(long sessionId, long participantId) {
        return "attention:" + sessionId + ":excluded:" + participantId;
    }
}
