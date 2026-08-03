package com.a105.zani.postclass.application.port;

import java.time.Instant;

import com.a105.zani.postclass.domain.model.PipelineStatus;

/**
 * 전이·재시도를 판단하는 데 필요한 작업의 현재 상태.
 *
 * @param status 현재 단계
 * @param attemptCount 현재 단계의 시도 횟수. 단계가 바뀌면 0 으로 돌아간다 — 재시도 예산은 단계마다 따로 준다
 * @param queuedAt 작업이 등록된 시각(= 메모 확정 시각). 8시간 마감의 기준점이다
 */
public record PipelineJobState(PipelineStatus status, int attemptCount, Instant queuedAt) {}
