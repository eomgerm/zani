package com.a105.zani.attention.infrastructure.redis;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.a105.zani.attention.application.exception.AttentionStateUnavailableException;
import com.a105.zani.attention.application.port.AttentionSnapshot;
import com.a105.zani.attention.application.port.AttentionStatePort;
import com.a105.zani.attention.application.port.ObservationApplied;
import com.a105.zani.attention.domain.model.AttentionState;
import com.a105.zani.attention.domain.model.DetectionRunTransition;
import com.a105.zani.attention.domain.model.UnmeasurableRun;

/**
 * 참여도 판정 상태를 Redis에 보관한다. 네 종류의 키를 쓰며 모두 TTL로 자연 소멸한다.
 *
 * <ul>
 *   <li>{@code attention:{sessionId}:{participantId}:event:{clientEventId}} — 멱등 판정용 표시(SETNX)
 *   <li>{@code attention:{sessionId}:state:{participantId}} — 현재 판정 상태
 *   <li>{@code attention:{sessionId}:significant:{state}:{participantId}} — 최근 5분 유의 상태 흔적
 *   <li>{@code attention:{sessionId}:excluded:{participantId}} — 집단 비율 분모 제외 표시
 *   <li>{@code attention:{sessionId}:outage:{participantId}} — 측정 불가 구간의 시작 지점(§7.1)
 *   <li>{@code attention:{sessionId}:run:unmeasurable:{participantId}} — UNMEASURABLE 연속 횟수(§7.3)
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
     * 더 최신 판정이 아직 없을 때만 연속 횟수와 반영 지점을 쓴다. 이미 있으면 아무것도 건드리지 않고 nil 을 돌려준다.
     *
     * <p>INCR 과 EXPIRE 를 왕복 두 번으로 나누면 그 사이에 연결이 끊겼을 때 TTL 없는 카운터가 남아, 한참 뒤 재입장한 학생이 옛 값을 그대로 물려받는다. 같은 이유로 현재 상태도 SET 한
     * 번으로 쓴다.
     *
     * <p>반영 지점을 뒤이은 별도 왕복으로 쓰면, 카운터는 올랐는데 반영 지점은 빠진 상태가 남는다. 그 뒤 재시도는 반영이 안 끝난 것으로 보고 카운터를 한 번 더 올린다.
     *
     * <p>순서 판단까지 이 안에서 하는 이유도 같다. 밖에서 읽고 비교한 뒤 쓰면, 같은 학생의 판정 두 건이 겹쳐 들어왔을 때 둘 다 "내가 최신"이라고 읽고 옛 쪽이 나중에 써서 반영 지점을 되돌린다.
     *
     * <p>측정 불가 구간(§7.1)도 여기서 잰다. 순서 판단을 통과한 오프셋은 항상 증가하므로 구간 길이가 음수가 될 수 없다. 따로 재면 통과한 관측들 사이에서 순서가 뒤바뀌어 음수가 나오고, 그러면
     * 측정이 불가한데도 분모로 되돌아간다. 구간이 없을 때는 {@code -1} 을 돌려준다.
     */
    private static final RedisScript<List> APPLY_OBSERVATION = RedisScript.of("""
            local ttl = tonumber(ARGV[2])
            local observed = tonumber(ARGV[3])
            local applied = redis.call('GET', KEYS[2])
            if applied and observed <= tonumber(applied) then
              return nil
            end
            local run = 0
            if ARGV[1] == 'INCREMENT' then
              run = redis.call('INCR', KEYS[1])
              redis.call('EXPIRE', KEYS[1], ttl)
            else
              redis.call('DEL', KEYS[1])
            end
            redis.call('SET', KEYS[2], ARGV[3], 'EX', ttl)
            local outage = -1
            if ARGV[4] == 'SUSPENDED' then
              local startedAt = redis.call('GET', KEYS[3])
              if startedAt then
                redis.call('EXPIRE', KEYS[3], ttl)
                outage = observed - tonumber(startedAt)
              else
                redis.call('SET', KEYS[3], ARGV[3], 'EX', ttl)
                outage = 0
              end
            else
              redis.call('DEL', KEYS[3])
            end
            return { run, outage }
            """, List.class);

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

    @Override
    public Set<Long> excludedFromDenominator(long sessionId, Collection<Long> participantIds) {
        if (participantIds.isEmpty()) {
            return Set.of();
        }
        List<Long> ordered = List.copyOf(participantIds);
        try {
            List<String> markers = redisTemplate
                    .opsForValue()
                    .multiGet(ordered.stream()
                            .map(participantId -> excludedKey(sessionId, participantId))
                            .toList());
            if (markers == null) {
                return Set.of();
            }
            Set<Long> excluded = new HashSet<>();
            for (int index = 0; index < ordered.size() && index < markers.size(); index++) {
                if (markers.get(index) != null) {
                    excluded.add(ordered.get(index));
                }
            }
            return excluded;
        } catch (DataAccessException exception) {
            throw new AttentionStateUnavailableException(exception);
        }
    }

    @Override
    public Optional<ObservationApplied> applyObservation(
            long sessionId,
            long participantId,
            DetectionRunTransition transition,
            boolean measurementSuspended,
            long observedOffsetMs,
            Duration ttl) {
        try {
            List<Long> result = redisTemplate.execute(
                    APPLY_OBSERVATION,
                    List.of(
                            unmeasurableRunKey(sessionId, participantId),
                            appliedKey(sessionId, participantId),
                            outageKey(sessionId, participantId)),
                    transition.unmeasurable().name(),
                    Long.toString(ttl.toSeconds()),
                    Long.toString(observedOffsetMs),
                    measurementSuspended ? "SUSPENDED" : "MEASURABLE");
            // nil 은 더 최신 판정이 이미 반영됐다는 뜻이다. 스크립트가 아무것도 건드리지 않았다.
            if (result == null || result.size() < 2) {
                return Optional.empty();
            }
            long outageMs = result.get(1);
            return Optional.of(new ObservationApplied(
                    new UnmeasurableRun(result.get(0).intValue()),
                    outageMs < 0 ? OptionalLong.empty() : OptionalLong.of(outageMs)));
        } catch (DataAccessException exception) {
            throw new AttentionStateUnavailableException(exception);
        }
    }

    @Override
    public void resetUnmeasurableRun(long sessionId, long participantId) {
        try {
            redisTemplate.delete(unmeasurableRunKey(sessionId, participantId));
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
