package com.a105.zani.audioclip.infrastructure.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.audioclip.application.exception.AudioClipStoreUnavailableException;
import com.a105.zani.audioclip.application.port.AudioClipRequestStorePort;
import com.a105.zani.audioclip.domain.model.AudioClipFailureReason;
import com.a105.zani.audioclip.domain.model.AudioClipRequest;
import com.a105.zani.audioclip.domain.model.AudioClipRequestStatus;

/**
 * 클립 요청 기록을 Redis JSON 문자열로 보관한다. 보존 기간(10분)이 지나면 자동 소멸하므로 별도 청소가 필요 없다. 만료 판정은 저장된 expiresAt 필드로 하며 키 TTL 은 멱등 처리·디버깅을
 * 위한 보존용이다. 세션별 PENDING 집합은 SSE 재구독 리플레이의 조회 인덱스로, 요청 키와 원자적으로 갱신되지 않아도 안전하다(고아 항목은 조회 시 걸러 정리한다).
 */
@Component
public class RedisAudioClipRequestStore implements AudioClipRequestStorePort {

    private static final String REQUEST_KEY_PREFIX = "audioclip:request:";
    private static final String PENDING_KEY_SUFFIX = ":pending";
    private static final String PENDING_KEY_PREFIX = "audioclip:session:";

    /** 요청 TTL(1분)보다 넉넉한 기록 보존 기간. 늦은 중복 업로드의 멱등 응답과 실패 사유 확인을 가능하게 한다. */
    private static final Duration RECORD_RETENTION = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;

    // Redis 문서 직렬화 형식은 이 어댑터의 소유라서 전역 ObjectMapper 빈 대신 자체 인스턴스를 쓴다(Instant 지원 모듈 포함).
    private final ObjectMapper objectMapper =
            JsonMapper.builder().addModule(new JavaTimeModule()).build();

    public RedisAudioClipRequestStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void save(AudioClipRequest request) {
        try {
            String json = objectMapper.writeValueAsString(RedisAudioClipRequestDocument.from(request));
            redisTemplate.opsForValue().set(requestKey(request.clipId()), json, RECORD_RETENTION);

            String pendingKey = pendingKey(request.sessionId());
            if (request.status() == AudioClipRequestStatus.PENDING) {
                redisTemplate.opsForSet().add(pendingKey, String.valueOf(request.clipId()));
                redisTemplate.expire(pendingKey, RECORD_RETENTION);
            } else {
                redisTemplate.opsForSet().remove(pendingKey, String.valueOf(request.clipId()));
            }
        } catch (JsonProcessingException | DataAccessException exception) {
            throw new AudioClipStoreUnavailableException(exception);
        }
    }

    @Override
    public Optional<AudioClipRequest> find(long clipId) {
        try {
            String json = redisTemplate.opsForValue().get(requestKey(clipId));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper
                    .readValue(json, RedisAudioClipRequestDocument.class)
                    .toDomain());
        } catch (JsonProcessingException | DataAccessException exception) {
            throw new AudioClipStoreUnavailableException(exception);
        }
    }

    @Override
    public List<AudioClipRequest> findPending(long sessionId) {
        try {
            Set<String> clipIds = redisTemplate.opsForSet().members(pendingKey(sessionId));
            if (clipIds == null || clipIds.isEmpty()) {
                return List.of();
            }

            List<AudioClipRequest> pending = new ArrayList<>();
            for (String clipId : clipIds) {
                Optional<AudioClipRequest> request = find(Long.parseLong(clipId));
                if (request.isEmpty()) {
                    // 기록이 보존 기간을 지나 소멸한 고아 항목은 집합에서도 정리한다.
                    redisTemplate.opsForSet().remove(pendingKey(sessionId), clipId);
                    continue;
                }
                if (request.get().status() == AudioClipRequestStatus.PENDING) {
                    pending.add(request.get());
                }
            }
            return pending;
        } catch (DataAccessException exception) {
            throw new AudioClipStoreUnavailableException(exception);
        }
    }

    private String requestKey(long clipId) {
        return REQUEST_KEY_PREFIX + clipId;
    }

    private String pendingKey(long sessionId) {
        return PENDING_KEY_PREFIX + sessionId + PENDING_KEY_SUFFIX;
    }

    /** Redis 저장 전용 표현. 도메인 모델의 필드 변경이 직렬화 형식에 조용히 새지 않도록 분리한다. */
    record RedisAudioClipRequestDocument(
            Long clipId,
            Long sessionId,
            Instant requestedAt,
            Instant expiresAt,
            String status,
            String failureReason,
            String transcriptText) {

        static RedisAudioClipRequestDocument from(AudioClipRequest request) {
            return new RedisAudioClipRequestDocument(
                    request.clipId(),
                    request.sessionId(),
                    request.requestedAt(),
                    request.expiresAt(),
                    request.status().name(),
                    request.failureReason() == null
                            ? null
                            : request.failureReason().name(),
                    request.transcriptText());
        }

        AudioClipRequest toDomain() {
            return AudioClipRequest.reconstitute(
                    clipId,
                    sessionId,
                    requestedAt,
                    expiresAt,
                    AudioClipRequestStatus.valueOf(status),
                    failureReason == null ? null : AudioClipFailureReason.valueOf(failureReason),
                    transcriptText);
        }
    }
}
