package com.a105.zani.report.infrastructure.persistence.entity;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.a105.zani.common.infrastructure.persistence.BaseJpaEntity;
import com.a105.zani.session.infrastructure.persistence.entity.SessionJpaEntity;

@Entity
@Table(name = "instructor_reports")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class InstructorReportJpaEntity extends BaseJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", referencedColumnName = "id", insertable = false, updatable = false)
    private SessionJpaEntity session;

    @Column(name = "overall_feedback", nullable = false, columnDefinition = "TEXT")
    private String overallFeedback;

    @Column(name = "question_count")
    private Integer questionCount;

    /**
     * <b>공개 게이트가 아니다.</b> 아무도 이 컬럼을 채우지 않는다 — 사후 파이프라인의 공개 단계는 {@code session_reports} 에만 시각을 찍는다 (S15P11A105-304). 조회는
     * 공통 리포트의 게시를 보므로({@code InstructorReportQueryPort#sessionReportPublished}) 이 값을 읽지 않는다.
     *
     * <p>여기를 게이트로 되돌리려면 공개 단계가 이 컬럼을 찍게 만드는 일이 먼저다. 순서를 바꾸면 분석이 정상 완주해도 강사 리포트가 영구히 404 다 — 실제로 그랬다.
     */
    @Column(name = "published_at", columnDefinition = "DATETIME(6)")
    private Instant publishedAt;
}
