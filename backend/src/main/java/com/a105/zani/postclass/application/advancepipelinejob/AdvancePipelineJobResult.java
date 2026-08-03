package com.a105.zani.postclass.application.advancepipelinejob;

import com.a105.zani.postclass.domain.model.PipelineStatus;

/**
 * 전이 후 작업의 단계.
 *
 * @param status 이 호출이 끝난 시점의 단계
 * @param advancedNow 이번 호출이 실제로 단계를 옮겼으면 {@code true}. 중복 보고여서 아무것도 바꾸지 않았으면 {@code false} — 다음 단계 시작이나 알림처럼 한 번만 일어나야
 *     하는 후속 작업은 이 값이 {@code true} 인 호출자만 한다.
 */
public record AdvancePipelineJobResult(PipelineStatus status, boolean advancedNow) {}
