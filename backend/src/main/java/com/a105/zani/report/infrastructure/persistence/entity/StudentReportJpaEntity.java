package com.a105.zani.report.infrastructure.persistence.entity;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.a105.zani.common.infrastructure.persistence.BaseJpaEntity;
import com.a105.zani.session.infrastructure.persistence.entity.SessionParticipantJpaEntity;

@Entity
@Table(name = "student_reports")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class StudentReportJpaEntity extends BaseJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "session_participant_id", nullable = false)
    private Long sessionParticipantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
        @JoinColumn(name = "session_id", referencedColumnName = "session_id", insertable = false, updatable = false),
        @JoinColumn(name = "session_participant_id", referencedColumnName = "id", insertable = false, updatable = false)
    })
    private SessionParticipantJpaEntity sessionParticipant;

    @Column(name = "participation_summary", nullable = false, columnDefinition = "TEXT")
    private String participationSummary;

    /**
     * 모델이 판단한 질문 수. 분석이 이 값을 내지 못했으면 {@code null} 이며 0 이 아니다 — 0 은 질문을 안 했다는 뜻이라 "판정이 없다"와 다르다. 채우는 쪽은 249(LLM 학생별
     * 분석)다.
     */
    @Column(name = "question_count")
    private Integer questionCount;

    @Column(name = "published_at", columnDefinition = "DATETIME(6)")
    private Instant publishedAt;
}
