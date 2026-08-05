package com.a105.zani.postclass.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.postclass.application.exception.TranscriptDocumentInvalidException;
import com.a105.zani.postclass.application.exception.TranscriptStoreUnavailableException;
import com.a105.zani.postclass.application.port.TranscriptPort;
import com.a105.zani.postclass.domain.model.TranscriptDocument;
import com.a105.zani.postclass.infrastructure.persistence.entity.TranscriptJpaEntity;
import com.a105.zani.postclass.infrastructure.persistence.repository.TranscriptJpaRepository;

/**
 * 최종 전사 영속 어댑터(S15P11A105-247).
 *
 * <p>{@code UK_TRANSCRIPTS_SESSION} 이 세션당 한 행을 강제하므로 저장은 upsert 한 문장이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TranscriptPersistenceAdapter implements TranscriptPort {

    private final TranscriptJpaRepository repository;

    // 컨텍스트에 공용 ObjectMapper 빈이 없어 문서 직렬화 전용으로 어댑터가 직접 소유한다
    // (PostClassTranscriptionChunkPersistenceAdapter 와 같은 방식).
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional
    public void save(Long sessionId, TranscriptDocument document, Instant now) {
        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(document);
        } catch (JsonProcessingException exception) {
            // 저장소 실패가 아니다. 직렬화할 값은 타입이 확정된 TranscriptDocument 이므로, 그것을
            // 문자열로 만들지 못한다는 것은 DB 상태가 아니라 코드·계약 문제다. 재시도로 풀리지 않는다.
            log.error("Cannot serialize the assembled transcript: sessionId={}", sessionId);
            throw new TranscriptDocumentInvalidException(exception);
        }
        try {
            // 새 행이면 1, 문서가 바뀐 기존 행이면 2, 값이 같아 바뀔 것이 없으면 0 이다(MySQL 규칙).
            // 세 경우 모두 정상이라 반환값으로 갈라 판단하지 않는다.
            repository.upsert(TsidGenerator.generate(), sessionId, serialized, now);
        } catch (DataAccessException exception) {
            throw new TranscriptStoreUnavailableException(exception);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TranscriptDocument> findBySessionId(Long sessionId) {
        Optional<TranscriptJpaEntity> stored;
        try {
            stored = repository.findBySessionId(sessionId);
        } catch (DataAccessException exception) {
            throw new TranscriptStoreUnavailableException(exception);
        }
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(stored.get().getTranscriptDocument(), TranscriptDocument.class));
        } catch (JsonProcessingException exception) {
            // 저장된 문서가 현재 형태와 다르거나 불변식을 어겼다. 같은 행을 다시 읽어도 같은 결과이므로
            // 재시도 가능한 저장소 실패로 분류하면 예산만 태운다.
            //
            // 빈 값으로 돌려주지 않는 이유는 그 편이 더 위험하기 때문이다 — "전사가 없다" 로 오인되면
            // 재조립이 일어나 읽지 못한 옛 문서를 덮어쓴다.
            log.error("Stored transcript does not match the current document contract: sessionId={}", sessionId);
            throw new TranscriptDocumentInvalidException(exception);
        }
    }
}
