package com.a105.zani.session.infrastructure.redis;

import java.time.Duration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.session.application.port.ReactionRateLimitPort;

/**
 * 참가자별 반응 간격을 Redis 키의 TTL 하나로 재는다.
 *
 * <p>토큰 버킷 같은 걸 두지 않은 이유: 필요한 건 "직전 반응 이후 충분히 지났는가" 하나뿐이고, 그건 {@code SET NX PX} 한 번으로 원자적으로 판정된다. 여러 서버 인스턴스가 붙어도 같은 키를
 * 보므로 판정이 갈리지 않는다.
 *
 * <p><b>Redis 장애 때는 통과시킨다.</b> 연타 제한은 화면이 지저분해지는 걸 막는 편의 장치라, 못 읽는다고 반응 기능 자체를 멈추는 건 과하다. 인증·권한처럼 막아야 할 것을 판정하는 자리가
 * 아니다.
 */
@Slf4j
@Component
public class ReactionRateLimitRedisAdapter implements ReactionRateLimitPort {

    /** 연타는 막되 손뼉을 두어 번 치는 정도는 통과시키는 간격. */
    private static final Duration INTERVAL = Duration.ofMillis(1_500);

    private final StringRedisTemplate redisTemplate;

    public ReactionRateLimitRedisAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryAcquire(long sessionId, String identity) {
        String key = "session:" + sessionId + ":reaction:cooldown:" + identity;
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", INTERVAL);
            return Boolean.TRUE.equals(acquired);
        } catch (DataAccessException unavailable) {
            log.warn("Redis 장애로 반응 간격을 재지 못해 통과시킵니다. sessionId={}", sessionId, unavailable);
            return true;
        }
    }
}
