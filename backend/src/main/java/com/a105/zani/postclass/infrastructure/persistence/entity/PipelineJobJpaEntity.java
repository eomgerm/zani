package com.a105.zani.postclass.infrastructure.persistence.entity;

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
 * <p>단계 문자열은 여기의 STATUS_* 상수만을 단일 소스로 쓴다. 대기 이후의 단계는 상태 머신(S15P11A105-104)이 더한다.
 */
@Entity
@Table(name = "pipeline_jobs")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PipelineJobJpaEntity extends BaseJpaEntity {

    public static final String STATUS_QUEUED = "QUEUED";

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "session_id", nullable = false, unique = true)
    private Long sessionId;

    @Column(name = "status", nullable = false, length = 30)
    private String status;
}
