package com.a105.zani.report.infrastructure.persistence.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.report.infrastructure.persistence.entity.StudentReportJpaEntity;

public interface StudentReportJpaRepository extends JpaRepository<StudentReportJpaEntity, Long> {

    /**
     * 개인 리포트 한 건.
     *
     * <p><b>{@code published_at} 으로 거르지 않는다.</b> 그 컬럼을 채우는 코드가 없어서, 거르면 분석이 정상 완주해도 학생이 리포트를 영구히 열 수 없다 — 강사 리포트가 같은
     * 이유로 404 였다(S15P11A105-310). 공개 여부는 공통 리포트의 게시로 판정한다 ({@code StudentReportQueryPort#sessionReportPublished}).
     */
    Optional<StudentReportJpaEntity> findBySessionIdAndSessionParticipantId(Long sessionId, Long participantId);
}
