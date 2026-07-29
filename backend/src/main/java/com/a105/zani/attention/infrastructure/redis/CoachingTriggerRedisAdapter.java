package com.a105.zani.attention.infrastructure.redis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.a105.zani.attention.application.exception.AttentionStateUnavailableException;
import com.a105.zani.attention.application.port.CoachingOutcome;
import com.a105.zani.attention.application.port.CoachingTipType;
import com.a105.zani.attention.application.port.CoachingTriggerStatePort;
import com.a105.zani.attention.application.port.PreviousCoachingTip;

/**
 * 코칭 트리거 상태를 Redis에 보관한다. 키 두 개를 쓰며 모두 TTL로 자연 소멸한다.
 *
 * <ul>
 *   <li>{@code attention:{sessionId}:coaching:open} — 열려 있는 트리거와 그 결과. 이 키의 생존이 곧 쿨타임이다
 *   <li>{@code attention:{sessionId}:coaching:last-tip} — 직전에 보여준 팁의 유형과 시각
 * </ul>
 *
 * <p>쿨타임을 별도 키로 두지 않는 이유는 두 값이 항상 같이 움직이기 때문이다. 나눠 두면 쿨타임은 끝났는데 결과 키가 남은 조합이 생겨, 강사가 10분 전 팁을 새 팁으로 받는다.
 *
 * <p>값은 JSON 으로 쓴다. 팁 문구는 LLM 이 채운 자유 텍스트라 줄바꿈과 구분자를 그대로 담을 수 있어, 다른 상태 키처럼 {@code |} 로 이어 붙이면 파싱이 깨진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoachingTriggerRedisAdapter implements CoachingTriggerStatePort {

    /** 직전 팁 유형 보관 기간. 세션 자동 만료(3시간)보다 길게 잡아 한 수업 안에서는 사라지지 않게 한다. */
    private static final Duration LAST_TIP_TTL = Duration.ofHours(4);

    /** 직전 팁 값 구분자. {@code 유형|표시시각(epoch millis)} 두 값뿐이고 둘 다 구분자를 담을 수 없다. */
    private static final String FIELD_SEPARATOR = "|";

    /**
     * 열린 트리거가 아직 있을 때만 결과를 채운다. 없으면 아무것도 하지 않는다.
     *
     * <p>{@code KEEPTTL} 로 쿨타임을 늘리지 않는다. 새로 SET 하면 팁이 늦게 완성될수록 쿨타임이 뒤로 밀려, 20초 걸린 트리거는 10분 20초를 쉰다.
     */
    private static final RedisScript<Long> COMPLETE_OUTCOME = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
              return 0
            end
            redis.call('SET', KEYS[1], ARGV[1], 'KEEPTTL')
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    public boolean openTrigger(long sessionId, String triggerId, Duration cooldown) {
        // SET NX 한 번으로 "열려 있는지 확인"과 "쿨타임 시작"을 함께 처리한다. 나누면 동시 폴링 두 건이 모두 통과한다.
        return execute(() -> Boolean.TRUE.equals(redisTemplate
                .opsForValue()
                .setIfAbsent(openKey(sessionId), write(CoachingOutcome.pending(triggerId)), cooldown)));
    }

    @Override
    public Optional<CoachingOutcome> openOutcome(long sessionId) {
        String stored = execute(() -> redisTemplate.opsForValue().get(openKey(sessionId)));
        if (stored == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(stored, CoachingOutcome.class));
        } catch (JacksonException | IllegalArgumentException exception) {
            // 배포 사이에 형태가 바뀐 값이 남은 경우다. 코칭만 한 주기 조용해지고 다음 트리거가 새로 쓴다.
            log.warn("코칭 트리거 결과를 읽을 수 없습니다. sessionId={}", sessionId, exception);
            return Optional.empty();
        }
    }

    @Override
    public void completeOutcome(long sessionId, CoachingOutcome outcome) {
        Long applied =
                execute(() -> redisTemplate.execute(COMPLETE_OUTCOME, List.of(openKey(sessionId)), write(outcome)));
        if (applied == null || applied == 0L) {
            log.info("쿨타임이 이미 끝나 팁을 표시하지 않습니다. sessionId={}, triggerId={}", sessionId, outcome.triggerId());
            return;
        }
        if (outcome.tip() != null) {
            rememberTip(sessionId, outcome.tip().tipType());
        }
    }

    @Override
    public Optional<PreviousCoachingTip> previousTip(long sessionId) {
        String stored = execute(() -> redisTemplate.opsForValue().get(lastTipKey(sessionId)));
        if (stored == null) {
            return Optional.empty();
        }
        int separator = stored.indexOf(FIELD_SEPARATOR);
        if (separator <= 0) {
            log.warn("직전 팁 값을 읽을 수 없습니다. sessionId={}", sessionId);
            return Optional.empty();
        }
        try {
            return Optional.of(new PreviousCoachingTip(
                    CoachingTipType.valueOf(stored.substring(0, separator)),
                    Instant.ofEpochMilli(Long.parseLong(stored.substring(separator + 1)))));
        } catch (IllegalArgumentException exception) {
            log.warn("직전 팁 값을 읽을 수 없습니다. sessionId={}", sessionId, exception);
            return Optional.empty();
        }
    }

    private void rememberTip(long sessionId, CoachingTipType tipType) {
        execute(() -> {
            redisTemplate
                    .opsForValue()
                    .set(
                            lastTipKey(sessionId),
                            tipType.name() + FIELD_SEPARATOR + clock.instant().toEpochMilli(),
                            LAST_TIP_TTL);
            return null;
        });
    }

    private String write(CoachingOutcome outcome) {
        return objectMapper.writeValueAsString(outcome);
    }

    private static String openKey(long sessionId) {
        return "attention:" + sessionId + ":coaching:open";
    }

    private static String lastTipKey(long sessionId) {
        return "attention:" + sessionId + ":coaching:last-tip";
    }

    /** Redis 장애는 애플리케이션 에러코드로 바꾼다. 코칭만 멈추고 수업은 계속한다. */
    private <T> T execute(RedisCall<T> call) {
        try {
            return call.run();
        } catch (DataAccessException exception) {
            throw new AttentionStateUnavailableException(exception);
        }
    }

    private interface RedisCall<T> {
        T run();
    }
}
