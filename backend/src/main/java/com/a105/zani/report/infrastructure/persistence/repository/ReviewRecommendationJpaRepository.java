package com.a105.zani.report.infrastructure.persistence.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.report.infrastructure.persistence.entity.ReviewRecommendationJpaEntity;

public interface ReviewRecommendationJpaRepository extends JpaRepository<ReviewRecommendationJpaEntity, Long> {

    List<ReviewRecommendationJpaEntity> findTop5ByStudentReportIdOrderByPriorityAscStartedOffsetMsAsc(
            Long studentReportId);
}
