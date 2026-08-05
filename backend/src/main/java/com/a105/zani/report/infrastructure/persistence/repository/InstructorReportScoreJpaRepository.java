package com.a105.zani.report.infrastructure.persistence.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.report.infrastructure.persistence.entity.InstructorReportScoreJpaEntity;

public interface InstructorReportScoreJpaRepository extends JpaRepository<InstructorReportScoreJpaEntity, Long> {

    /** 화면의 도넛 순서를 서버가 정하지 않는다. 저장 순서를 그대로 주고 배치는 화면이 정한다. */
    List<InstructorReportScoreJpaEntity> findByInstructorReportIdOrderByIdAsc(Long instructorReportId);
}
