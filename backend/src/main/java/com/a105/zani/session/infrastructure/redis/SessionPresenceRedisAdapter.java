package com.a105.zani.session.infrastructure.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.session.application.exception.SessionPresenceUnavailableException;
import com.a105.zani.session.application.port.SessionPresencePort;

/**
 * presence를 Redis 문자열 키의 TTL로 보관한다. presence 키는 heartbeat마다 갱신되어 TTL이 지나면 사라지고, 강사 유예 키에는 마감 시각(epoch millis)을 담아 만료
 * 평가에 쓴다. Redis 장애는 애플리케이션 에러코드로 변환한다.
 */
@Component
@RequiredArgsConstructor
public class SessionPresenceRedisAdapter implements SessionPresencePort {

    private static final String PRESENCE_VALUE = "1";

    private final StringRedisTemplate redisTemplate;

    @Override
    public void recordHeartbeat(long sessionId, long participantId, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(presenceKey(sessionId, participantId), PRESENCE_VALUE, ttl);
        } catch (DataAccessException exception) {
            throw new SessionPresenceUnavailableException(exception);
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
