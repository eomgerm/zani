package com.a105.zani.session.infrastructure.redis;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.session.application.port.RaisedHandChange;
import com.a105.zani.session.application.port.RaisedHandQueuePort;

/**
 * 손든 참가자 큐를 Redis Sorted Set 으로 들고 있다.
 *
 * <p><b>현재 상태만 여기 둔다.</b> 이력은 {@code interaction_events} 가 따로 남긴다. 지금 손든 사람을 그 표에서 파생하려면 참가자별 최신 행을 뽑는 윈도 함수 질의가 필요한데,
 * 화면이 볼 때마다 그걸 돌릴 이유가 없다.
 *
 * <p><b>Sorted Set 이지만 순번을 제공하지는 않는다.</b> score(서버 수신 시각)로 안정적인 순서가 나오긴 하나, 같은 밀리초에 들어온 멤버는 Redis 가 identity 사전순으로
 * 정렬하므로 실제 도착 순서와 달라진다. 화면이 포함 여부만 쓰기로 해서 받아들인 한계다 — 순번을 노출하게 되면 세션별 {@code INCR} 순번을 score 로 쓰고 발급과 {@code ZADD} 를 Lua
 * 로 묶어야 한다. 지금 집합으로 바꾸지 않는 이유는 그때 되돌릴 일을 만들지 않기 위해서일 뿐, 비용 차이는 없다.
 *
 * <p><b>읽기는 조용히 실패한다.</b> 못 읽으면 목록이 비어 보일 뿐 사실과 다른 것을 주장하지 않는다. 쓰기는 반대로
 * {@link com.a105.zani.session.application.port.RaisedHandChange#UNAVAILABLE} 로 알려 호출한 쪽이 브로드캐스트를 멈추게 한다 — 기록하지 못했는데
 * 알리면 화면에는 손이 올라가 있고 서버는 모르는 상태가 된다.
 */
@Slf4j
@Component
public class RaisedHandQueueRedisAdapter implements RaisedHandQueuePort {

    /** 수업 자동 종료(3시간)보다 길게 잡아 한 수업 안에서는 사라지지 않게 한다. */
    private static final Duration TTL = Duration.ofHours(4);

    private final StringRedisTemplate redisTemplate;

    public RaisedHandQueueRedisAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public RaisedHandChange raise(long sessionId, String identity, long raisedAtMillis) {
        try {
            // ZADD 는 이미 있는 멤버의 score 를 덮어쓴다. NX 를 붙여 자리를 그대로 둔다 — 재시도나
            // 두 번 누름이 목록을 흔들면 화면이 불필요하게 다시 그려진다.
            Boolean added = redisTemplate.opsForZSet().addIfAbsent(key(sessionId), identity, (double) raisedAtMillis);
            if (added == null) {
                // 파이프라인·트랜잭션 모드에서만 나오는 값이라 여기서는 오지 않아야 한다. 온다면 기록 여부를 알 수 없다.
                log.warn("손들기 기록 결과를 알 수 없습니다. sessionId={}", sessionId);
                return RaisedHandChange.UNAVAILABLE;
            }
            refreshTtl(sessionId);
            return added ? RaisedHandChange.CHANGED : RaisedHandChange.UNCHANGED;
        } catch (DataAccessException unavailable) {
            log.warn("Redis 장애로 손들기를 기록하지 못했습니다. sessionId={}", sessionId, unavailable);
            return RaisedHandChange.UNAVAILABLE;
        }
    }

    @Override
    public RaisedHandChange lower(long sessionId, String identity) {
        try {
            Long removed = redisTemplate.opsForZSet().remove(key(sessionId), identity);
            if (removed == null) {
                log.warn("손내리기 결과를 알 수 없습니다. sessionId={}", sessionId);
                return RaisedHandChange.UNAVAILABLE;
            }
            return removed > 0 ? RaisedHandChange.CHANGED : RaisedHandChange.UNCHANGED;
        } catch (DataAccessException unavailable) {
            log.warn("Redis 장애로 손내리기를 기록하지 못했습니다. sessionId={}", sessionId, unavailable);
            return RaisedHandChange.UNAVAILABLE;
        }
    }

    /**
     * 만료 갱신 실패는 기록 자체를 무르지 않는다.
     *
     * <p>손은 이미 큐에 들어갔다. 여기서 실패했다고 실패로 뒤집으면 저장된 상태를 알리지 않게 되어, 화면과 서버가 이번에는 반대 방향으로 어긋난다. TTL 은 수업이 끝난 뒤 청소용 안전장치라 한 번
     * 놓쳐도 그 수업은 정상 동작한다.
     */
    private void refreshTtl(long sessionId) {
        try {
            redisTemplate.expire(key(sessionId), TTL);
        } catch (DataAccessException unavailable) {
            log.warn("손들기 큐의 만료 시간을 갱신하지 못했습니다. sessionId={}", sessionId, unavailable);
        }
    }

    @Override
    public List<String> raisedInOrder(long sessionId) {
        try {
            Set<String> ordered = redisTemplate.opsForZSet().range(key(sessionId), 0, -1);
            return ordered == null ? List.of() : List.copyOf(ordered);
        } catch (DataAccessException unavailable) {
            log.warn("Redis 장애로 손든 참가자를 읽지 못했습니다. sessionId={}", sessionId, unavailable);
            return List.of();
        }
    }

    private static String key(long sessionId) {
        return "session:" + sessionId + ":hands";
    }
}
