package com.a105.zani.postclass.application.advancepipelinejob;

import com.a105.zani.postclass.domain.model.PipelineStatus;

/**
 * 세션의 사후 처리 작업을 어느 단계로 옮길지.
 *
 * <p>작업 ID 가 아니라 세션 ID 로 지목한다 — 작업은 세션당 한 행이고(pipeline_jobs.session_id UNIQUE), 단계를 보고하는 쪽도 세션 단위로 움직인다.
 */
public record AdvancePipelineJobCommand(Long sessionId, PipelineStatus targetStatus) {}
