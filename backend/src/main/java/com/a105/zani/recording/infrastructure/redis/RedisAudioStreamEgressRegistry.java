package com.a105.zani.recording.infrastructure.redis;

import java.time.Duration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.recording.application.port.AudioStreamEgressRegistryPort;

/**
 * 코칭용 스트림 Egress 식별자를 Redis 문자열 키로 표시해 둔다.
 *
 * <p>Redis 장애를 예외로 올리지 않는다. 이 표시는 webhook 을 조용히 넘기기 위한 보조 정보이고, 없으면 기존 경로(녹화 미준비로 보고 재전송 유도)로 떨어질 뿐이라 녹화·코칭 기능 자체를 막을
 * 이유가 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisAudioStreamEgressRegistry implements AudioStreamEgressRegistryPort {

    private static final String KEY_PREFIX = "recording:audio-stream-egress:";

    /** 수업 최대 길이(3시간)보다 넉넉히. 만료돼도 해당 Egress 는 이미 끝난 뒤다. */
    private static final Duration RETENTION = Duration.ofHours(6);

    private final StringRedisTemplate redisTemplate;

    @Override
    public void remember(String egressId, long sessionId) {
        if (egressId == null || egressId.isBlank()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key(egressId), String.valueOf(sessionId), RETENTION);
        } catch (DataAccessException exception) {
            log.warn("Could not remember audio stream egress {}: {}", egressId, exception.getMessage());
        }
    }

    @Override
    public boolean isAudioStream(String egressId) {
        if (egressId == null || egressId.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(egressId)));
        } catch (DataAccessException exception) {
            log.warn("Could not check audio stream egress {}: {}", egressId, exception.getMessage());
            return false;
        }
    }

    private String key(String egressId) {
        return KEY_PREFIX + egressId;
    }
}
