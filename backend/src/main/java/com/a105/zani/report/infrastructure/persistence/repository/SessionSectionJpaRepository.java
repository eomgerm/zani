package com.a105.zani.report.infrastructure.persistence.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.report.infrastructure.persistence.entity.SessionSectionJpaEntity;

public interface SessionSectionJpaRepository extends JpaRepository<SessionSectionJpaEntity, Long> {

    List<SessionSectionJpaEntity> findBySessionIdOrderByStartedOffsetMsAsc(Long sessionId);
}
