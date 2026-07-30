package com.a105.zani.session.infrastructure.redis;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
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

    /**
     * 선점과 기존 값 조회를 한 번의 왕복으로 처리한다.
     *
     * <p>두 명령으로 나누면 중간에 끊길 창이 생긴다. SET NX 가 false 를 돌려줘 <b>재시도임을 이미 확정한 뒤</b> GET 이 실패하면, 같은 catch 에 걸려 "처음 보는 값"으로
     * 내려가고 이미 저장된 메시지가 한 번 더 저장·브로드캐스트된다. 행은 리포트에 영구히 남고 화면에도 두 번 보인다.
     *
     * <p>스크립트로 합치면 그 창이 사라진다. 호출이 실패하면 그때는 중복인지 <b>모르는</b> 상태이므로 전송을 살리는 판단이 옳다 — 아는 것과 모르는 것을 구분하는 것이 핵심이다.
     *
     * <p>반환: 처음 보는 값이면 nil, 재시도면 처음 부여했던 {@code eventId}.
     */
    private static final RedisScript<String> CLAIM = RedisScript.of("""
            if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then
              return nil
            end
            return redis.call('GET', KEYS[1])
            """, String.class);

    private final StringRedisTemplate redisTemplate;

    public ChatIdempotencyRedisAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Optional<String> claim(long sessionId, String clientEventId, String eventId) {
        try {
            // 선점에 실패했는데 값이 비어 있으면 그 사이 TTL 이 끝난 것이다. 재시도로 볼 근거가 없어 새 전송으로 취급한다.
            return Optional.ofNullable(redisTemplate.execute(
                    CLAIM, List.of(key(sessionId, clientEventId)), eventId, String.valueOf(TTL.toMillis())));
        } catch (DataAccessException unavailable) {
            log.warn("Redis 장애로 채팅 재시도 중복 검사를 건너뜁니다. sessionId={}", sessionId, unavailable);
            return Optional.empty();
        }
    }

    @Override
    public void reclaim(long sessionId, String clientEventId, String eventId) {
        try {
            // NX 없이 덮어쓴다. 이미 있는 선점을 새 식별자로 바꾸는 것이 목적이라 "없을 때만" 조건이 붙으면 안 된다.
            redisTemplate.opsForValue().set(key(sessionId, clientEventId), eventId, TTL);
        } catch (DataAccessException unavailable) {
            // 못 바꿔도 전송은 이어간다. claim 과 같은 판단이다 — 중복 한 건보다 채팅이 멎는 쪽이 나쁘다.
            log.warn("채팅 멱등 선점을 새 식별자로 바꾸지 못했습니다. sessionId={}", sessionId, unavailable);
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
