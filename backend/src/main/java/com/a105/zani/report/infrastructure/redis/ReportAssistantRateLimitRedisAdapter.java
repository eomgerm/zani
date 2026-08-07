package com.a105.zani.report.infrastructure.redis;

import java.time.Duration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.report.application.port.ReportAssistantRateLimitPort;

/**
 * 참여자별 질문 간격을 Redis 키의 TTL 하나로 잰다(S15P11A105-259).
 *
 * <p>토큰 버킷을 두지 않은 이유는 필요한 판정이 "직전 질문 이후 충분히 지났는가" 하나뿐이고, 그건 {@code SET NX PX} 한 번으로 원자적으로 끝나기 때문이다 — 반응 간격 제한
 * ({@code ReactionRateLimitRedisAdapter})과 같은 구조다.
 *
 * <p><b>Redis 장애 때는 막는다(fail-closed). 반응 쪽과 반대다.</b> 그쪽의 기준은 "Redis 가 진실의 출처인가 보조 장치인가" 였고, 반응은 본체가 브로드캐스트로 살아 있어 게이트만
 * 잃으므로 여는 쪽을 골랐다. 여기서 잃는 것은 게이트가 아니라 <b>비용</b>이다 — 질문 한 번이 곧 GMS 유료 호출이라, 장애 구간 동안 열어 두면 한 사람이 팀 크레딧을 소진시킬 수 있고 그건 되돌릴
 * 수 없다. 반대로 막았을 때의 대가는 그 시간 동안 질문을 못 하는 것뿐이고, 리포트 화면의 다른 기능은 그대로 돈다.
 */
@Slf4j
@Component
public class ReportAssistantRateLimitRedisAdapter implements ReportAssistantRateLimitPort {

    /**
     * 질문 사이 최소 간격.
     *
     * <p>GMS 응답이 5~12초쯤 걸리므로 사람이 답을 읽고 다음 질문을 쓰는 흐름은 이 간격에 걸리지 않는다. 걸리는 것은 같은 질문을 연타하거나 스크립트로 부르는 경우다.
     */
    private static final Duration INTERVAL = Duration.ofSeconds(5);

    private final StringRedisTemplate redisTemplate;

    public ReportAssistantRateLimitRedisAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryAcquire(long sessionId, long sessionParticipantId) {
        String key = "session:" + sessionId + ":assistant:cooldown:" + sessionParticipantId;
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "1", INTERVAL));
        } catch (DataAccessException unavailable) {
            log.warn(
                    "Redis 장애로 질문 간격을 재지 못해 막습니다. sessionId={} participantId={}",
                    sessionId,
                    sessionParticipantId,
                    unavailable);
            return false;
        }
    }
}
