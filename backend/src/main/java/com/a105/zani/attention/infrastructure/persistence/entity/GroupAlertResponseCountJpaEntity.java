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

@Entity
@Table(name = "group_alert_response_counts")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class GroupAlertResponseCountJpaEntity extends BaseCreatedJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "group_alert_id", nullable = false)
    private Long groupAlertId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_alert_id", referencedColumnName = "id", insertable = false, updatable = false)
    private GroupAlertJpaEntity groupAlert;

    @Column(name = "response_type", nullable = false, length = 30)
    private String responseType;

    @Column(name = "response_count", nullable = false)
    private Integer responseCount;
}
