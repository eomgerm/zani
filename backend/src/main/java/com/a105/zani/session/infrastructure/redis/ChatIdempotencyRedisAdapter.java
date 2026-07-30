package com.a105.zani.session.infrastructure.redis;

import java.time.Duration;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.session.application.port.ChatIdempotencyPort;

/**
 * {@code clientEventId} → {@code eventId} 기록. 키 하나에 짧은 TTL 만 쓴다.
 *
 * <p><b>Redis 장애 때는 중복 제거를 포기하고 전송을 살린다.</b> 다른 상태 저장(코칭 트리거 등)은 장애 시 503 으로 기능을 멈추지만, 채팅은 판단이 다르다 — 중복 메시지 한 건은 눈에
 * 거슬리는 정도인데, Redis 가 깜빡일 때 수업 중 채팅이 멎으면 그건 장애로 보인다.
 */
@Slf4j
@Component
public class ChatIdempotencyRedisAdapter implements ChatIdempotencyPort {

    /** 재시도는 몇 초 안에 온다. 오래 들고 있어도 얻는 게 없고 키만 쌓인다. */
    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;

    public ChatIdempotencyRedisAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Optional<String> claim(long sessionId, String clientEventId, String eventId) {
        String key = key(sessionId, clientEventId);
        try {
            // SET NX 한 번으로 "처음 보는 값인지"와 "선점"을 함께 처리한다. 나누면 동시 전송 두 건이 모두 통과한다.
            if (Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, eventId, TTL))) {
                return Optional.empty();
            }
            // 선점에 실패했으면 먼저 처리된 값이 있다. 그 사이 TTL 이 끝나 사라졌다면 재시도로 볼 근거가 없으므로 새 전송으로 취급한다.
            return Optional.ofNullable(redisTemplate.opsForValue().get(key));
        } catch (DataAccessException unavailable) {
            log.warn("Redis 장애로 채팅 재시도 중복 검사를 건너뜁니다. sessionId={}", sessionId, unavailable);
            return Optional.empty();
        }
    }

    @Override
    public void release(long sessionId, String clientEventId) {
        try {
            redisTemplate.delete(key(sessionId, clientEventId));
        } catch (DataAccessException unavailable) {
            // 지우지 못해도 TTL 로 사라진다. 그 사이 같은 재시도가 오면 한 건이 유실되지만, 저장 실패 뒤의 드문 경우다.
            log.warn("채팅 멱등 키를 되돌리지 못했습니다. sessionId={}", sessionId, unavailable);
        }
    }

    private static String key(long sessionId, String clientEventId) {
        return "session:" + sessionId + ":chat:sent:" + clientEventId;
    }
}
