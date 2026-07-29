package com.a105.zani.attention.infrastructure.redis;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.a105.zani.attention.application.exception.AttentionStateUnavailableException;
import com.a105.zani.attention.application.port.AttentionSnapshot;
import com.a105.zani.attention.application.port.AttentionStatePort;
import com.a105.zani.attention.domain.model.AttentionState;
import com.a105.zani.attention.domain.model.DetectionRunCounters;
import com.a105.zani.attention.domain.model.DetectionRunTransition;

/**
 * 참여도 판정 상태를 Redis에 보관한다. 네 종류의 키를 쓰며 모두 TTL로 자연 소멸한다.
 *
 * <ul>
 *   <li>{@code attention:{sessionId}:{participantId}:event:{clientEventId}} — 멱등 판정용 표시(SETNX)
 *   <li>{@code attention:{sessionId}:state:{participantId}} — 현재 판정 상태
 *   <li>{@code attention:{sessionId}:significant:{state}:{participantId}} — 최근 5분 유의 상태 흔적
 *   <li>{@code attention:{sessionId}:excluded:{participantId}} — 집단 비율 분모 제외 표시
 *   <li>{@code attention:{sessionId}:outage:{participantId}} — 측정 불가 구간의 시작 지점(§7.1)
 *   <li>{@code attention:{sessionId}:run:{kind}:{participantId}} — 검출기 출력 연속 횟수(§4.1)
 *   <li>{@code attention:{sessionId}:applied:{participantId}} — 마지막으로 반영한 판정 창 종료 시각
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

    /**
     * 더 최신 판정이 아직 없을 때만 연속 카운터와 반영 지점을 쓴다. 이미 있으면 아무것도 건드리지 않고 nil 을 돌려준다.
     *
     * <p>INCR 과 EXPIRE 를 왕복 두 번으로 나누면 그 사이에 연결이 끊겼을 때 TTL 없는 카운터가 남아, 한참 뒤 재입장한 학생이 옛 값을 그대로 물려받는다. 같은 이유로 현재 상태도 SET 한
     * 번으로 쓴다.
     *
     * <p>반영 지점을 뒤이은 별도 왕복으로 쓰면, 카운터는 올랐는데 반영 지점은 빠진 상태가 남는다. 그 뒤 재시도는 반영이 안 끝난 것으로 보고 카운터를 한 번 더 올린다.
     *
     * <p>순서 판단까지 이 안에서 하는 이유도 같다. 밖에서 읽고 비교한 뒤 쓰면, 같은 학생의 판정 두 건이 겹쳐 들어왔을 때 둘 다 "내가 최신"이라고 읽고 옛 쪽이 나중에 써서 반영 지점을 되돌린다.
     */
    private static final RedisScript<List> APPLY_OBSERVATION = RedisScript.of("""
            local function apply(key, step, ttl)
              if step == 'INCREMENT' then
                local value = redis.call('INCR', key)
                redis.call('EXPIRE', key, ttl)
                return value
              elseif step == 'RESET' then
                redis.call('DEL', key)
                return 0
              else
                local value = redis.call('GET', key)
                if value then
                  redis.call('EXPIRE', key, ttl)
                  return tonumber(value)
                end
                return 0
              end
            end
            local ttl = tonumber(ARGV[3])
            local applied = redis.call('GET', KEYS[3])
            if applied and tonumber(applied) >= tonumber(ARGV[4]) then
              return nil
            end
            local counters = { apply(KEYS[1], ARGV[1], ttl), apply(KEYS[2], ARGV[2], ttl) }
            redis.call('SET', KEYS[3], ARGV[4], 'EX', ttl)
            return counters
            """, List.class);

    /**
     * 측정 불가 구간의 시작 지점을 심거나 지우고, 지금까지 이어진 길이를 돌려준다(§7.1).
     *
     * <p>읽고-쓰기로 나누면 같은 학생의 관측이 겹쳐 들어올 때 둘 다 "내가 이 구간의 시작"이라고 판단해, 1분을 넘긴 구간이 계속 0으로 되돌아간다. 시작 지점은 처음 한 번만 심고 이후에는 TTL 만
     * 늘린다.
     */
    private static final RedisScript<Long> TRACK_OUTAGE = RedisScript.of("""
            local ttl = tonumber(ARGV[3])
            if ARGV[1] ~= 'SUSPENDED' then
              redis.call('DEL', KEYS[1])
              return -1
            end
            local startedAt = redis.call('GET', KEYS[1])
            if not startedAt then
              redis.call('SET', KEYS[1], ARGV[2], 'EX', ttl)
              return 0
            end
            redis.call('EXPIRE', KEYS[1], ttl)
            return tonumber(ARGV[2]) - tonumber(startedAt)
            """, Long.class);

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
    public OptionalLong trackMeasurementOutage(
            long sessionId, long participantId, boolean suspended, long observedOffsetMs, Duration ttl) {
        try {
            Long outageMs = redisTemplate.execute(
                    TRACK_OUTAGE,
                    List.of(outageKey(sessionId, participantId)),
                    suspended ? "SUSPENDED" : "MEASURABLE",
                    Long.toString(observedOffsetMs),
                    Long.toString(ttl.toSeconds()));
            // -1 은 측정이 가능해져 구간이 없다는 뜻이다.
            return outageMs == null || outageMs < 0 ? OptionalLong.empty() : OptionalLong.of(outageMs);
        } catch (DataAccessException exception) {
            throw new AttentionStateUnavailableException(exception);
        }
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

    @Override
    public Optional<DetectionRunCounters> applyObservation(
            long sessionId,
            long participantId,
            DetectionRunTransition transition,
            long observedOffsetMs,
            Duration ttl) {
        try {
            List<Long> counters = redisTemplate.execute(
                    APPLY_OBSERVATION,
                    List.of(
                            lowRunKey(sessionId, participantId),
                            unmeasurableRunKey(sessionId, participantId),
                            appliedKey(sessionId, participantId)),
                    transition.lowEngagement().name(),
                    transition.unmeasurable().name(),
                    Long.toString(ttl.toSeconds()),
                    Long.toString(observedOffsetMs));
            // nil 은 더 최신 판정이 이미 반영됐다는 뜻이다. 스크립트가 아무것도 건드리지 않았다.
            if (counters == null || counters.size() < 2) {
                return Optional.empty();
            }
            return Optional.of(new DetectionRunCounters(
                    counters.get(0).intValue(), counters.get(1).intValue()));
        } catch (DataAccessException exception) {
            throw new AttentionStateUnavailableException(exception);
        }
    }

    @Override
    public void resetRuns(long sessionId, long participantId) {
        try {
            redisTemplate.delete(
                    List.of(lowRunKey(sessionId, participantId), unmeasurableRunKey(sessionId, participantId)));
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

    private String lowRunKey(long sessionId, long participantId) {
        return "attention:" + sessionId + ":run:low:" + participantId;
    }

    private String unmeasurableRunKey(long sessionId, long participantId) {
        return "attention:" + sessionId + ":run:unmeasurable:" + participantId;
    }

    private String appliedKey(long sessionId, long participantId) {
        return "attention:" + sessionId + ":applied:" + participantId;
    }

    private String outageKey(long sessionId, long participantId) {
        return "attention:" + sessionId + ":outage:" + participantId;
    }
}
