package com.a105.zani.postclass.domain.model;

import java.util.EnumSet;
import java.util.Set;

/** 사후 처리 작업(pipeline_jobs 행)의 단계. V6 스키마의 status 컬럼 값과 일치한다. */
public enum PipelineStatus {
    QUEUED,
    TRANSCRIBING,
    ANALYZING,
    VALIDATING,
    PUBLISHED,
    FAILED;

    private static final Set<PipelineStatus> ANALYSIS_STAGES = EnumSet.of(ANALYZING, VALIDATING);

    /**
     * 분석 오케스트레이션이 이어받을 수 있는 단계.
     *
     * <p>{@code VALIDATING} 이 포함되는 이유: 세 분석을 끝낸 실행은 그 단계로 옮긴 뒤 공개를 시도하는데, 리포트가 갖춰지지 않아 공개가 거절되면 작업이 거기 남는다. 이어받지 않으면 그
     * 세션은 8시간 마감까지 멈춘다. 이어받은 실행은 세 분석의 멱등 겹을 GMS 없이 통과해 공개만 다시 시도한다.
     */
    public static Set<PipelineStatus> analysisStages() {
        return ANALYSIS_STAGES;
    }
}
