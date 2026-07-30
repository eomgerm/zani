package com.a105.zani.session.infrastructure.redis;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.a105.zani.session.application.exception.ScreenShareStateUnavailableException;
import com.a105.zani.session.application.port.ActiveScreenSharePort;

/**
 * 세션의 활성 화면 공유자를 Redis 문자열 키로 보관한다. 값은 지금 공유 중인 참가자 ID이고, TTL이 지나면 사라진다. Redis 장애는 애플리케이션 에러코드로 변환한다.
 *
 * <p>획득·갱신·해제를 Lua로 원자화한다. GET 후 SET을 애플리케이션에서 둘로 나누면, 두 참가자가 동시에 "비어 있음"을 읽고 둘 다 심어 "한 번에 하나"가 깨진다.
 */
@Component
@RequiredArgsConstructor
public class ActiveScreenShareRedisAdapter implements ActiveScreenSharePort {

    /** 비어 있거나 이미 내가 소유했으면 값을 심고 TTL을 갱신한다. 다른 참가자가 소유 중이면 건드리지 않는다. 획득/갱신은 1, 거부는 0. */
    private static final RedisScript<Long> CLAIM = RedisScript.of("""
            local current = redis.call('GET', KEYS[1])
            if current == false or current == ARGV[1] then
              redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
              return 1
            end
            return 0
            """, Long.class);

    /** 내가 소유한 경우에만 해제한다. 다른 참가자가 이미 공유 중이면 그 슬롯을 지우지 않는다. */
    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean claim(long sessionId, long participantId, Duration ttl) {
        try {
            Long acquired = redisTemplate.execute(
                    CLAIM,
                    List.of(activeShareKey(sessionId)),
                    Long.toString(participantId),
                    Long.toString(ttl.toSeconds()));
            return acquired != null && acquired == 1L;
        } catch (DataAccessException exception) {
            throw new ScreenShareStateUnavailableException(exception);
        }
    }

    @Override
    public void release(long sessionId, long participantId) {
        try {
            redisTemplate.execute(RELEASE, List.of(activeShareKey(sessionId)), Long.toString(participantId));
        } catch (DataAccessException exception) {
            throw new ScreenShareStateUnavailableException(exception);
        }
    }

    @Override
    public Optional<Long> currentSharer(long sessionId) {
        try {
            String value = redisTemplate.opsForValue().get(activeShareKey(sessionId));
            return value == null ? Optional.empty() : Optional.of(Long.parseLong(value));
        } catch (DataAccessException exception) {
            throw new ScreenShareStateUnavailableException(exception);
        }
    }

    private String activeShareKey(long sessionId) {
        return "session:" + sessionId + ":screenshare";
    }
}
