package com.a105.zani.recording.infrastructure.persistence;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.recording.application.exception.RecordingOutboxUnavailableException;
import com.a105.zani.recording.application.port.NewRecordingOutboxMessage;
import com.a105.zani.recording.application.port.PendingRecordingOutboxMessage;
import com.a105.zani.recording.application.port.RecordingOutboxStore;
import com.a105.zani.recording.application.port.RecordingOutboxType;
import com.a105.zani.recording.application.port.TrackEgressPayload;
import com.a105.zani.recording.infrastructure.persistence.entity.RecordingOutboxJpaEntity;
import com.a105.zani.recording.infrastructure.persistence.repository.RecordingOutboxJpaRepository;

/**
 * recording_outbox 영속 어댑터. enqueue는 INSERT IGNORE로 dedup_key UNIQUE 충돌을 호출자 트랜잭션 오염 없이 흡수하고, claim/requeue는 원자 UPDATE로
 * 릴레이의 중복 수행을 막는다. payload는 JSON으로 직렬화한다(익명 alias만 포함).
 */
@Component
@RequiredArgsConstructor
public class RecordingOutboxPersistenceAdapter implements RecordingOutboxStore {

    private static final String EMPTY_PAYLOAD = "{}";

    private final RecordingOutboxJpaRepository outboxJpaRepository;
    // 컨텍스트에 공용 ObjectMapper 빈이 없어 payload 직렬화 전용으로 어댑터가 직접 소유한다(record 직렬화는 Jackson 기본 지원).
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean enqueue(NewRecordingOutboxMessage message) {
        try {
            int inserted = outboxJpaRepository.insertIgnore(
                    TsidGenerator.generate(),
                    message.dedupKey(),
                    message.type().name(),
                    message.sessionId(),
                    writePayload(message.payload()),
                    Instant.now());
            return inserted == 1;
        } catch (DataAccessException exception) {
            throw new RecordingOutboxUnavailableException(exception);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<PendingRecordingOutboxMessage> fetchDue(int limit, Instant now) {
        try {
            return outboxJpaRepository
                    .findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                            RecordingOutboxJpaEntity.STATUS_PENDING, now, PageRequest.of(0, limit))
                    .stream()
                    .map(this::toPendingMessage)
                    .toList();
        } catch (DataAccessException exception) {
            throw new RecordingOutboxUnavailableException(exception);
        }
    }

    @Override
    @Transactional
    public boolean claim(Long id, Instant now) {
        try {
            return outboxJpaRepository.claim(id, now) == 1;
        } catch (DataAccessException exception) {
            throw new RecordingOutboxUnavailableException(exception);
        }
    }

    @Override
    @Transactional
    public void requeueExpiredClaims(Instant cutoff, Instant now) {
        try {
            outboxJpaRepository.requeueExpiredClaims(cutoff, now);
        } catch (DataAccessException exception) {
            throw new RecordingOutboxUnavailableException(exception);
        }
    }

    @Override
    @Transactional
    public void markCompleted(Long id) {
        outboxJpaRepository.findById(id).ifPresent(RecordingOutboxJpaEntity::complete);
    }

    @Override
    @Transactional
    public void markRetry(Long id, String error, Instant nextAttemptAt) {
        outboxJpaRepository.findById(id).ifPresent(entity -> entity.scheduleRetry(truncate(error), nextAttemptAt));
    }

    @Override
    @Transactional
    public void markFailed(Long id, String error) {
        outboxJpaRepository.findById(id).ifPresent(entity -> entity.fail(truncate(error)));
    }

    private PendingRecordingOutboxMessage toPendingMessage(RecordingOutboxJpaEntity entity) {
        return new PendingRecordingOutboxMessage(
                entity.getId(),
                entity.getDedupKey(),
                RecordingOutboxType.valueOf(entity.getOutboxType()),
                entity.getSessionId(),
                readPayload(entity.getPayload()),
                entity.getAttemptCount());
    }

    private String writePayload(TrackEgressPayload payload) {
        if (payload == null) {
            return EMPTY_PAYLOAD;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new RecordingOutboxUnavailableException(exception);
        }
    }

    private TrackEgressPayload readPayload(String payload) {
        if (payload == null || payload.isBlank() || EMPTY_PAYLOAD.equals(payload)) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, TrackEgressPayload.class);
        } catch (JsonProcessingException exception) {
            throw new RecordingOutboxUnavailableException(exception);
        }
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }
}
