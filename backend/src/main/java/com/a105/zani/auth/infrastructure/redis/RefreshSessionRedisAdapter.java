package com.a105.zani.auth.infrastructure.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.a105.zani.auth.application.exception.RefreshSessionUnavailableException;
import com.a105.zani.auth.application.port.RefreshSession;
import com.a105.zani.auth.application.port.RefreshSessionPort;

@Component
public class RefreshSessionRedisAdapter implements RefreshSessionPort {

    private static final String KEY_PREFIX = "auth:refresh:";
    private static final DefaultRedisScript<Long> ROTATE_SCRIPT = new DefaultRedisScript<>(
            """
            local currentSubject = redis.call('GET', KEYS[1])
            if not currentSubject or currentSubject ~= ARGV[1] then
                return 0
            end
            redis.call('DEL', KEYS[1])
            redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[2])
            return 1
            """,
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public RefreshSessionRedisAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean rotate(
            String currentTokenId,
            String subject,
            RefreshSession replacement) {
        long timeToLiveMillis = Duration.between(
                Instant.now(), replacement.expiresAt()).toMillis();
        if (timeToLiveMillis <= 0) {
            return false;
        }

        try {
            Long result = redisTemplate.execute(
                    ROTATE_SCRIPT,
                    List.of(key(subject, currentTokenId), key(subject, replacement.tokenId())),
                    subject,
                    Long.toString(timeToLiveMillis));
            return Long.valueOf(1L).equals(result);
        } catch (DataAccessException exception) {
            throw new RefreshSessionUnavailableException(exception);
        }
    }

    private String key(String subject, String tokenId) {
        return KEY_PREFIX + "{" + subject + "}:" + tokenId;
    }
}
