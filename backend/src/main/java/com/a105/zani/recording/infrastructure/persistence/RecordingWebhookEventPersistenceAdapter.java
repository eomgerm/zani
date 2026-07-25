package com.a105.zani.recording.infrastructure.persistence;

import java.time.Instant;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.recording.application.exception.WebhookEventStoreUnavailableException;
import com.a105.zani.recording.application.port.RecordingWebhookEventStore;
import com.a105.zani.recording.infrastructure.persistence.entity.RecordingWebhookEventJpaEntity;
import com.a105.zani.recording.infrastructure.persistence.repository.RecordingWebhookEventJpaRepository;

/** webhook 이벤트 내구 저장 어댑터. event_id UNIQUE + INSERT IGNORE로 중복 이벤트를 흡수하고, PROCESSED 여부로 재처리를 판정한다. */
@Component
@RequiredArgsConstructor
public class RecordingWebhookEventPersistenceAdapter implements RecordingWebhookEventStore {

    private final RecordingWebhookEventJpaRepository eventJpaRepository;

    @Override
    @Transactional
    public boolean begin(String eventId, String eventType, String payload) {
        try {
            int inserted = eventJpaRepository.insertIgnore(
                    TsidGenerator.generate(), eventId, eventType, payload, Instant.now());
            if (inserted == 1) {
                return true;
            }
            // 이미 저장된 이벤트: PROCESSED면 중복(false), RECEIVED로 남아 있으면 이전 처리 실패분이므로 재처리(true).
            return eventJpaRepository
                    .findByEventId(eventId)
                    .map(entity -> !RecordingWebhookEventJpaEntity.STATUS_PROCESSED.equals(entity.getStatus()))
                    .orElse(true);
        } catch (DataAccessException exception) {
            throw new WebhookEventStoreUnavailableException(exception);
        }
    }

    @Override
    @Transactional
    public void markProcessed(String eventId) {
        eventJpaRepository.findByEventId(eventId).ifPresent(RecordingWebhookEventJpaEntity::markProcessed);
    }
}
