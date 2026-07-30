package com.a105.zani.attention.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.a105.zani.common.infrastructure.persistence.BaseCreatedJpaEntity;
import com.a105.zani.session.infrastructure.persistence.entity.SessionJpaEntity;

@Entity
@Table(name = "group_alerts")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class GroupAlertJpaEntity extends BaseCreatedJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", referencedColumnName = "id", insertable = false, updatable = false)
    private SessionJpaEntity session;

    @Column(name = "alert_type", nullable = false, length = 50)
    private String alertType;

    @Column(name = "window_started_offset_ms", nullable = false)
    private Long windowStartedOffsetMs;

    @Column(name = "window_ended_offset_ms", nullable = false)
    private Long windowEndedOffsetMs;

    @Column(name = "numerator_count", nullable = false)
    private Integer numeratorCount;

    @Column(name = "denominator_count", nullable = false)
    private Integer denominatorCount;

    @Column(name = "occurred_offset_ms", nullable = false)
    private Long occurredOffsetMs;

    @Column(name = "trigger_id", length = 64)
    private String triggerId;

    @Column(name = "triggered_at", columnDefinition = "DATETIME(6)")
    private Instant triggeredAt;

    @Column(name = "significant_ratio", precision = 7, scale = 6)
    private BigDecimal significantRatio;

    @Column(name = "confused_ratio", precision = 7, scale = 6)
    private BigDecimal confusedRatio;

    @Column(name = "missed_ratio", precision = 7, scale = 6)
    private BigDecimal missedRatio;

    @Column(name = "non_response_ratio", precision = 7, scale = 6)
    private BigDecimal nonResponseRatio;

    @Column(name = "unmeasurable_ratio", precision = 7, scale = 6)
    private BigDecimal unmeasurableRatio;

    @Column(name = "transcript_status", length = 30)
    private String transcriptStatus;

    @Column(name = "transcript_text", columnDefinition = "TEXT")
    private String transcriptText;

    @Column(name = "transcript_started_at", columnDefinition = "DATETIME(6)")
    private Instant transcriptStartedAt;

    @Column(name = "transcript_ended_at", columnDefinition = "DATETIME(6)")
    private Instant transcriptEndedAt;

    @Column(name = "tip_type", length = 50)
    private String tipType;

    @Column(name = "tip_title", length = 200)
    private String tipTitle;

    @Column(name = "tip_message", columnDefinition = "TEXT")
    private String tipMessage;

    @Column(name = "target_concept", length = 500)
    private String targetConcept;

    @Column(name = "unavailable_reason", length = 50)
    private String unavailableReason;
}
