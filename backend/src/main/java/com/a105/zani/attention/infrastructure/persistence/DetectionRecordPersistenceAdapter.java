package com.a105.zani.attention.infrastructure.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.attention.domain.model.DetectionRecord;
import com.a105.zani.attention.domain.repository.DetectionRecordRepository;
import com.a105.zani.common.persistence.TsidGenerator;

/**
 * 판정 기록을 attention_events 에 남긴다.
 *
 * <p>중복은 예외가 아니라 {@code INSERT IGNORE} 로 걸러낸다. JPA 로 넣고 제약 위반을 잡으면 flush 시점에 트랜잭션이 rollback-only 로 표시돼, 잡아서 무시해도 커밋이
 * 실패한다. recording outbox 가 같은 이유로 같은 방식을 쓴다.
 *
 * <p>JPA 를 거치지 않으므로 {@code @CreatedDate} 감사도 타지 않는다. {@code created_at} 은 직접 넣되 {@code NOW(6)} 이 아니라
 * {@code UTC_TIMESTAMP(6)} 을 쓴다 — Hibernate 가 {@code jdbc.time_zone: UTC} 로 쓰는 다른 테이블과 시간대를 맞추기 위해서다.
 */
@Component
@RequiredArgsConstructor
public class DetectionRecordPersistenceAdapter implements DetectionRecordRepository {

    private static final String INSERT_IGNORE = """
            INSERT IGNORE INTO attention_events
                (id, session_id, session_participant_id, detector_outcome, attention_score,
                 occurred_offset_ms, window_started_offset_ms, signal_quality,
                 feature_schema_version, client_event_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean saveIfNew(DetectionRecord record) {
        int inserted = jdbcTemplate.update(
                INSERT_IGNORE,
                TsidGenerator.generate(),
                record.sessionId(),
                record.participantId(),
                record.outcome().name(),
                record.outcome().engagementLevel().orElse(null),
                record.occurredOffsetMs(),
                record.windowStartedOffsetMs(),
                record.signalQuality(),
                record.featureSchemaVersion(),
                record.clientEventId());
        return inserted > 0;
    }
}
