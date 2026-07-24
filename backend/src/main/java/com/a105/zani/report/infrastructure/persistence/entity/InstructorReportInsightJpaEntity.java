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
@Table(name = "instructor_report_insights")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class InstructorReportInsightJpaEntity extends BaseJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "instructor_report_id", nullable = false)
    private Long instructorReportId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instructor_report_id", referencedColumnName = "id", insertable = false, updatable = false)
    private InstructorReportJpaEntity instructorReport;

    @Column(name = "insight_type", nullable = false, length = 30)
    private String insightType;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "started_offset_ms")
    private Long startedOffsetMs;

    @Column(name = "ended_offset_ms")
    private Long endedOffsetMs;
}
