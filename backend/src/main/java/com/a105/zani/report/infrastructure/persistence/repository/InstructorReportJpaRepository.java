package com.a105.zani.report.infrastructure.persistence.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.report.infrastructure.persistence.entity.InstructorReportJpaEntity;

public interface InstructorReportJpaRepository extends JpaRepository<InstructorReportJpaEntity, Long> {

    /** 세션당 리포트는 하나다. */
    Optional<InstructorReportJpaEntity> findBySessionId(Long sessionId);
}
