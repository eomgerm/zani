package com.a105.zani.attention.infrastructure.persistence.entity;

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
}
