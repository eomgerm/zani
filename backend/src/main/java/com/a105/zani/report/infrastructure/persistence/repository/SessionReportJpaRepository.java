package com.a105.zani.report.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.a105.zani.report.infrastructure.persistence.entity.SessionReportJpaEntity;

public interface SessionReportJpaRepository extends JpaRepository<SessionReportJpaEntity, Long> {

    boolean existsBySessionId(Long sessionId);

    /** 게시 전 초안은 조회 대상이 아니다. 화면에 나갈 값은 게시된 행에서만 나온다. */
    Optional<SessionReportJpaEntity> findBySessionIdAndPublishedAtIsNotNull(Long sessionId);

    /**
     * 아직 공개되지 않은 리포트에만 공개 시각을 찍는다. 위 조회가 화면에 값을 내보내기 시작하는 지점이 여기다(S15P11A105-304).
     *
     * <p>{@code published_at is null} 조건이 핵심이다. 조건 없이 덮으면 재시도가 시각을 다시 써 알림 발견 순서가 흔들리고, 두 번째 호출이 첫 번째와 구분되지 않는다. 조건이
     * 있으므로 <b>호출자가 0 행을 반드시 확인해야 한다</b> — 0 은 "이미 공개됐거나 리포트가 없다" 는 뜻이고 성공이 아니다.
     *
     * <p>{@code updated_at} 을 함께 쓰는 이유: 벌크 UPDATE 는 {@code @LastModifiedDate} 리스너를 타지 않는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update SessionReportJpaEntity report
               set report.publishedAt = :publishedAt, report.updatedAt = :publishedAt
             where report.sessionId = :sessionId and report.publishedAt is null
            """)
    int markPublished(@Param("sessionId") Long sessionId, @Param("publishedAt") Instant publishedAt);
}
