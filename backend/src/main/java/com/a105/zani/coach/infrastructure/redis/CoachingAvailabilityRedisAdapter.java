package com.a105.zani.coach.infrastructure.redis;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.coach.application.port.CoachingAvailabilityPort;

/** 세션별 코칭 가용 상태를 Redis 에 저장한다. 키: {@code coach:available:{sessionId}}, 값: "true"|"false", TTL 4시간(세션 최대 시간+버퍼). */
@Component
public class CoachingAvailabilityRedisAdapter implements CoachingAvailabilityPort {

    private static final String KEY_PREFIX = "coach:available:";
    private static final Duration TTL = Duration.ofHours(4);

    private final StringRedisTemplate redisTemplate;

    public CoachingAvailabilityRedisAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void store(long sessionId, boolean available) {
        redisTemplate.opsForValue().set(key(sessionId), Boolean.toString(available), TTL);
    }

    @Override
    public Optional<Boolean> find(long sessionId) {
        String value = redisTemplate.opsForValue().get(key(sessionId));
        return value == null ? Optional.empty() : Optional.of(Boolean.parseBoolean(value));
    }

    private String key(long sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
