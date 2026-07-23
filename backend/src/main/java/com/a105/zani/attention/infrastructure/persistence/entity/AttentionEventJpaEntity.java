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

    @Column(name = "attention_score", nullable = false)
    private Byte attentionScore;

    @Column(name = "occurred_offset_ms", nullable = false)
    private Long occurredOffsetMs;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "signal_quality", precision = 5, scale = 4)
    private BigDecimal signalQuality;
}
