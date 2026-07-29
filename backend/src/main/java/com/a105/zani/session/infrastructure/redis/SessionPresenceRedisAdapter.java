package com.a105.zani.session.infrastructure.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.a105.zani.session.application.exception.SessionPresenceUnavailableException;
import com.a105.zani.session.application.port.SessionPresencePort;

/**
 * presence를 Redis 문자열 키의 TTL로 보관한다. presence 키는 heartbeat마다 갱신되어 TTL이 지나면 사라지고, 값에는 <b>이 연속 접속이 시작된 시각</b>(epoch
 * millis)을 담는다. 강사 유예 키도 같은 방식으로 마감 시각을 담아 만료 평가에 쓴다. Redis 장애는 애플리케이션 에러코드로 변환한다.
 *
 * <p>값이 단순 표시였을 때는 "언제부터 붙어 있는지"를 알 수 없어 집계 분모의 연속 접속 1분 조건(§7)을 판단할 수 없었다.
 */
@Component
@RequiredArgsConstructor
public class SessionPresenceRedisAdapter implements SessionPresencePort {

    /** presence 값 접두사. 시작 시각을 담기 전 값({@code "1"})과 구분하기 위해 붙인다. */
    private static final String CONNECTED_SINCE_PREFIX = "since:";

    /**
     * 연속 접속 시작 시각을 처음 한 번만 심고, 이후 heartbeat 는 TTL 만 늘린다.
     *
     * <p>읽고-쓰기로 나누면 같은 참가자의 heartbeat 가 겹쳐 들어올 때 둘 다 "내가 시작"이라고 판단해 시작 시각이 계속 지금으로 밀린다. 그러면 연속 접속 1분이 영원히 채워지지 않는다.
     *
     * <p>값에 접두사를 붙여 저장하는 이유는 시작 시각을 담기 전에 심긴 키의 값이 {@code "1"} 이라 그것도 숫자로 읽히기 때문이다. 숫자로 받아들이면 1970년부터 접속한 것이 되어 그 참가자가
     * <b>즉시</b> 분모에 들어간다. 접두사가 없으면 지금을 시작으로 다시 심고, 1분 뒤 정상적으로 들어온다.
     */
    private static final RedisScript<String> TOUCH_PRESENCE = RedisScript.of("""
            local stored = redis.call('GET', KEYS[1])
            if stored and string.match(stored, '^since:%d+$') then
              redis.call('EXPIRE', KEYS[1], ARGV[2])
              return stored
            end
            local value = 'since:' .. ARGV[1]
            redis.call('SET', KEYS[1], value, 'EX', ARGV[2])
            return value
            """, String.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public Instant recordHeartbeat(long sessionId, long participantId, Instant now, Duration ttl) {
        try {
            String stored = redisTemplate.execute(
                    TOUCH_PRESENCE,
                    List.of(presenceKey(sessionId, participantId)),
                    Long.toString(now.toEpochMilli()),
                    Long.toString(ttl.toSeconds()));
            Instant startedAt = parseStartedAt(stored);
            return startedAt == null ? now : startedAt;
        } catch (DataAccessException exception) {
            throw new SessionPresenceUnavailableException(exception);
        }
    }

    @Override
    public Map<Long, Instant> connectedSince(long sessionId, Collection<Long> participantIds) {
        if (participantIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ordered = List.copyOf(participantIds);
        try {
            List<String> values = redisTemplate
                    .opsForValue()
                    .multiGet(ordered.stream()
                            .map(participantId -> presenceKey(sessionId, participantId))
                            .toList());
            if (values == null) {
                return Map.of();
            }
            Map<Long, Instant> connected = new HashMap<>();
            for (int index = 0; index < ordered.size() && index < values.size(); index++) {
                Instant startedAt = parseStartedAt(values.get(index));
                if (startedAt != null) {
                    connected.put(ordered.get(index), startedAt);
                }
            }
            return connected;
        } catch (DataAccessException exception) {
            throw new SessionPresenceUnavailableException(exception);
        }
    }

    /** 접속이 끊겼거나(키 없음) 시작 시각을 담기 전에 심긴 값이면 null. 후자는 다음 heartbeat 가 다시 심는다. */
    private Instant parseStartedAt(String value) {
        if (value == null || !value.startsWith(CONNECTED_SINCE_PREFIX)) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(value.substring(CONNECTED_SINCE_PREFIX.length())));
        } catch (NumberFormatException notAnInstant) {
            return null;
        }
    }

    @Override
    public void clearPresence(long sessionId, long participantId) {
        try {
            redisTemplate.delete(presenceKey(sessionId, participantId));
        } catch (DataAccessException exception) {
            throw new SessionPresenceUnavailableException(exception);
        }
    }

    @Override
    public void startInstructorGrace(long sessionId, Instant deadline, Duration ttl) {
        try {
            // 진행 중인 유예가 없을 때만 마감 시각을 심는다(SETNX, 원자적). 반복·동시 이탈로 마감 시각이 갱신되지 않도록 한다.
            redisTemplate.opsForValue().setIfAbsent(graceKey(sessionId), Long.toString(deadline.toEpochMilli()), ttl);
        } catch (DataAccessException exception) {
            throw new SessionPresenceUnavailableException(exception);
        }
    }

    @Override
    public void clearInstructorGrace(long sessionId) {
        try {
            redisTemplate.delete(graceKey(sessionId));
        } catch (DataAccessException exception) {
            throw new SessionPresenceUnavailableException(exception);
        }
    }

    @Override
    public Optional<Instant> instructorGraceDeadline(long sessionId) {
        try {
            String value = redisTemplate.opsForValue().get(graceKey(sessionId));
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(Instant.ofEpochMilli(Long.parseLong(value)));
        } catch (DataAccessException exception) {
            throw new SessionPresenceUnavailableException(exception);
        }
    }

    private String presenceKey(long sessionId, long participantId) {
        return "session:" + sessionId + ":presence:" + participantId;
    }

    private String graceKey(long sessionId) {
        return "session:" + sessionId + ":instructor-grace";
    }
}
