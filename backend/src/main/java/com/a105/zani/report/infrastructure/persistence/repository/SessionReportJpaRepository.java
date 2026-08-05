package com.a105.zani.report.infrastructure.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.report.infrastructure.persistence.entity.SessionReportJpaEntity;

public interface SessionReportJpaRepository extends JpaRepository<SessionReportJpaEntity, Long> {

    boolean existsBySessionId(Long sessionId);
}
