package com.a105.zani.postclass.domain.model;

/** 사후 처리 작업(pipeline_jobs 행)의 단계. V6 스키마의 status 컬럼 값과 일치한다. */
public enum PipelineStatus {
    QUEUED,
    TRANSCRIBING,
    ANALYZING,
    VALIDATING,
    PUBLISHED,
    FAILED
}
