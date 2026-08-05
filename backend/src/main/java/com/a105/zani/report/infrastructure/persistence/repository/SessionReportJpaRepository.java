package com.a105.zani.report.infrastructure.persistence.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.report.infrastructure.persistence.entity.SessionReportJpaEntity;

public interface SessionReportJpaRepository extends JpaRepository<SessionReportJpaEntity, Long> {

    boolean existsBySessionId(Long sessionId);

    /** 게시 전 초안은 조회 대상이 아니다. 화면에 나갈 값은 게시된 행에서만 나온다. */
    Optional<SessionReportJpaEntity> findBySessionIdAndPublishedAtIsNotNull(Long sessionId);
}
