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
 * <p><b>왜 Sorted Set 인가.</b> 요구사항이 "서버 수신 시각 기준 순번"인데, score 에 시각을 넣으면 자료구조 자체가 순서를 보장한다. 목록·집합으로 두면 순서를 따로 관리해야 하고,
 * {@code interaction_events} 에서 파생하려면 참가자별 최신 행을 뽑는 윈도 함수 질의가 필요하다.
 *
 * <p><b>Redis 장애 때는 조용히 실패한다.</b> 손들기는 화면 표시용이라, 못 읽으면 목록이 비어 보일 뿐 수업이 멈추지는 않는다. 이력은 {@code interaction_events} 가 따로
 * 남기므로 리포트도 잃지 않는다.
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
            // ZADD 는 이미 있는 멤버의 score 를 덮어쓴다. NX 를 붙여 먼저 든 순번을 지킨다 —
            // 두 번 눌렀다고 뒤로 밀리면 안 된다.
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
