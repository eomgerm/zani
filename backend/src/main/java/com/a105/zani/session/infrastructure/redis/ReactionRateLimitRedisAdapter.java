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
 * <p><b>Redis 장애 때는 통과시킨다(fail-open). 의도한 트레이드오프다.</b> 연타 제한은 화면이 지저분해지는 걸 막는 편의 장치라, 못 읽는다고 반응 기능 자체를 멈추는 건 과하다.
 * 인증·권한처럼 막아야 할 것을 판정하는 자리가 아니다.
 *
 * <p>대가는 분명하다 — <b>장애 구간 동안 한 사람의 연타가 참가자 수만큼 증폭된다.</b> 그럼에도 여는 쪽을 고른 이유는, 화면이 잠깐 지저분한 것은 지나가지만 수업 중에 반응이 안 나가는 것은 되돌릴
 * 방법이 없기 때문이다. 이 위험은 기능별 제한으로 덮이지 않는다(채팅에는 애초에 제한이 없다). 채널 단위 발행 제한을 별도 티켓으로 두는 것이 맞는 해법이다.
 *
 * <p><b>손들기 큐({@link RaisedHandQueueRedisAdapter})는 반대로 닫는다.</b> 기준은 Redis 가 <i>진실의 출처</i>인가 <i>보조 장치</i>인가다. 손들기는
 * Redis 에 든 값이 곧 상태라, 기록하지 못했는데 알리면 화면과 서버가 어긋난다. 반면 반응은 본체가 브로드캐스트와 {@code interaction_events} 로 살아 있고 여기서 잃는 건
 * 게이트뿐이다. 채팅 멱등도 같은 이유로 열어 둔다 — 닫으면 메시지가 사라지는데 그건 중복보다 나쁘다.
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
