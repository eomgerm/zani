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
 * <p><b>보조 수단이다.</b> webhook 분류의 1차 근거는 이벤트 페이로드에 실려 오는 출력 종류이고( {@code RecordingWebhookEvent.egressAudioStream}), 이
 * 표시는 페이로드에 track 정보가 없는 이벤트만 받아 낸다.
 *
 * <p>그래서 Redis 장애를 예외로 올리지 않는다. 다만 <em>1차 근거가 없는 이벤트에 한해</em> 표시가 사라지면 그 Egress 는 녹화 미준비로 오해받아 5xx 가 나가고 LiveKit 이 무한
 * 재전송한다. 예전에는 이 표시가 유일한 근거라 Redis 가 죽으면 곧바로 그 증상이 났다. 여기서 조용히 삼키는 선택은 1차 근거가 있다는 전제 위에 서 있다.
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
