package com.a105.zani.report.infrastructure.persistence.entity;

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

import com.a105.zani.common.infrastructure.persistence.BaseJpaEntity;

@Entity
@Table(name = "review_recommendations")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ReviewRecommendationJpaEntity extends BaseJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "student_report_id", nullable = false)
    private Long studentReportId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_report_id", referencedColumnName = "id", insertable = false, updatable = false)
    private StudentReportJpaEntity studentReport;

    @Column(name = "recommendation_type", nullable = false, length = 30)
    private String recommendationType;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "started_offset_ms", nullable = false)
    private Long startedOffsetMs;

    @Column(name = "ended_offset_ms", nullable = false)
    private Long endedOffsetMs;

    @Column(name = "priority", nullable = false)
    private Byte priority;
}
