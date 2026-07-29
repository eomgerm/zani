package com.a105.zani.attention.infrastructure.persistence.entity;

import java.math.BigDecimal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.a105.zani.common.infrastructure.persistence.BaseCreatedJpaEntity;
import com.a105.zani.session.infrastructure.persistence.entity.SessionParticipantJpaEntity;

/**
 * 판정 기록 테이블 매핑.
 *
 * <p>쓰기는 {@code DetectionRecordPersistenceAdapter} 의 {@code INSERT IGNORE} 로만 한다. 이 매핑이 남아 있는 것은
 * {@code CheckPromptEvidenceJpaEntity} 의 FK 연관 대상이자 스키마 검증 기준이기 때문이다.
 */
@Entity
@Table(name = "attention_events")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AttentionEventJpaEntity extends BaseCreatedJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "session_participant_id", nullable = false)
    private Long sessionParticipantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
        @JoinColumn(name = "session_id", referencedColumnName = "session_id", insertable = false, updatable = false),
        @JoinColumn(name = "session_participant_id", referencedColumnName = "id", insertable = false, updatable = false)
    })
    private SessionParticipantJpaEntity sessionParticipant;

    /**
     * 검출기 출력 7종.
     *
     * <p>아래 계약 컬럼들이 nullable 인 것은 확장 단계이기 때문이다. 티켓 78 시절 행에는 값이 없다. 계약 이전 행을 정리한 뒤 스키마와 이 매핑을 함께 NOT NULL 로 조인다.
     *
     * <p>{@code low_engagement}·{@code engine_version} 컬럼은 티켓 61 에서 받지 않기로 해 항상 NULL 이다. 매핑에서 뺐고, 컬럼 제거는 계약 마이그레이션에서 함께
     * 판단한다.
     */
    @Column(name = "detector_outcome", length = 30)
    private String detectorOutcome;

    /** 4단계 값. 4단계가 아닌 출력(UNMEASURABLE·CAMERA_OFF·DETECTOR_UNAVAILABLE)은 비어 있다. */
    @Column(name = "attention_score")
    private Byte attentionScore;

    @Column(name = "occurred_offset_ms", nullable = false)
    private Long occurredOffsetMs;

    /** 10초 창 시작 시각. 보는 순간 확정되는 출력은 창이 없어 비어 있다(§4.2). */
    @Column(name = "window_started_offset_ms")
    private Long windowStartedOffsetMs;

    @Column(name = "feature_schema_version", length = 40)
    private String featureSchemaVersion;

    @Column(name = "client_event_id", length = 64)
    private String clientEventId;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "signal_quality", precision = 5, scale = 4)
    private BigDecimal signalQuality;
}
