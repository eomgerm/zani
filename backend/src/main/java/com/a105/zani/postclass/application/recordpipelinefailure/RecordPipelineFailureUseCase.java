package com.a105.zani.postclass.application.recordpipelinefailure;

import com.a105.zani.postclass.application.exception.PipelineJobNotFoundException;

/** 사후 처리 단계의 실패를 기록하고, 재시도 정책에 따라 다시 시도하거나 작업을 접는다. 전사·분석·검증 각 단계가 실패했을 때 부른다. */
public interface RecordPipelineFailureUseCase {

    /** @throws PipelineJobNotFoundException 세션의 작업이 없음 */
    RecordPipelineFailureResult record(RecordPipelineFailureCommand command);
}
