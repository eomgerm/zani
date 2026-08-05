package com.a105.zani.report.infrastructure.persistence.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.report.infrastructure.persistence.entity.InstructorReportInsightJpaEntity;

public interface InstructorReportInsightJpaRepository extends JpaRepository<InstructorReportInsightJpaEntity, Long> {

    /** 구간이 이른 것부터. 수업 전체를 가리키는 인사이트({@code started_offset_ms} 가 {@code null})는 앞에 온다 — 특정 구간 이야기보다 먼저 읽는 것이 자연스럽다. */
    List<InstructorReportInsightJpaEntity> findByInstructorReportIdOrderByStartedOffsetMsAscIdAsc(
            Long instructorReportId);
}
