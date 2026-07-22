package com.a105.zani.session.infrastructure.redis;

import com.a105.zani.session.application.exception.SessionLockUnavailableException;
import com.a105.zani.session.application.port.SessionActivationLockPort;
import java.time.Duration;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class SessionActivationLockRedisAdapter implements SessionActivationLockPort {

    private static final String KEY_PREFIX = "session:active-lock:";
    private static final String LOCK_VALUE = "1";

    private final StringRedisTemplate redisTemplate;

    public SessionActivationLockRedisAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryAcquire(long instructorId, Duration ttl) {
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key(instructorId), LOCK_VALUE, ttl);
            return Boolean.TRUE.equals(acquired);
        } catch (DataAccessException exception) {
            throw new SessionLockUnavailableException(exception);
        }
    }

    @Override
    public void release(long instructorId) {
        try {
            redisTemplate.delete(key(instructorId));
        } catch (DataAccessException exception) {
            throw new SessionLockUnavailableException(exception);
        }
    }

    private String key(long instructorId) {
        return KEY_PREFIX + instructorId;
    }
}
