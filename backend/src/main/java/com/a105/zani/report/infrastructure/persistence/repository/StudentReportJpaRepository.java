package com.a105.zani.report.infrastructure.persistence.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.report.infrastructure.persistence.entity.StudentReportJpaEntity;

public interface StudentReportJpaRepository extends JpaRepository<StudentReportJpaEntity, Long> {

    Optional<StudentReportJpaEntity> findBySessionIdAndSessionParticipantIdAndPublishedAtIsNotNull(
            Long sessionId, Long participantId);
}
