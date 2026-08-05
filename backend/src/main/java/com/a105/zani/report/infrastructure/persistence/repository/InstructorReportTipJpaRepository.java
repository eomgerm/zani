package com.a105.zani.report.infrastructure.persistence.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.report.infrastructure.persistence.entity.InstructorReportTipJpaEntity;

public interface InstructorReportTipJpaRepository extends JpaRepository<InstructorReportTipJpaEntity, Long> {

    /** 팁에는 순서를 정할 값이 없다. 저장 순서를 그대로 준다. */
    List<InstructorReportTipJpaEntity> findByInstructorReportIdOrderByIdAsc(Long instructorReportId);
}
