package com.a105.zani.coach.infrastructure.redis;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.coach.application.port.TranscriptStorePort;

/**
 * 세션별 최신 전사 텍스트를 Redis 에 보관한다. 키: {@code coach:transcript:{sessionId}}, TTL 10분. 팁 생성(204)이 이 값을 참조한다. (S15P11A105-203)
 */
@Component
public class TranscriptRedisAdapter implements TranscriptStorePort {

    private static final String KEY_PREFIX = "coach:transcript:";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;

    public TranscriptRedisAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void store(long sessionId, String transcript) {
        redisTemplate.opsForValue().set(key(sessionId), transcript == null ? "" : transcript, TTL);
    }

    @Override
    public Optional<String> find(long sessionId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key(sessionId)));
    }

    private String key(long sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
