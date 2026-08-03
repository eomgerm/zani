package com.a105.zani.postclass.infrastructure.persistence.entity;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.a105.zani.common.infrastructure.persistence.BaseJpaEntity;

/**
 * pipeline_jobs 행. 메모가 확정된 세션 하나의 사후 처리 작업이다.
 *
 * <p>단계 문자열은 {@link com.a105.zani.postclass.domain.model.PipelineStatus} 만을 단일 소스로 쓴다. 전이 규칙은
 * {@link com.a105.zani.postclass.domain.model.PipelineStateMachine} 이 가진다.
 */
@Entity
@Table(name = "pipeline_jobs")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PipelineJobJpaEntity extends BaseJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "session_id", nullable = false, unique = true)
    private Long sessionId;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "next_attempt_at", columnDefinition = "DATETIME(6)")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError;
}
