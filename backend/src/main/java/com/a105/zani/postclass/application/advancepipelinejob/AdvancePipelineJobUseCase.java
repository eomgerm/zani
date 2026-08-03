package com.a105.zani.postclass.application.advancepipelinejob;

import com.a105.zani.postclass.application.exception.IllegalPipelineTransitionException;
import com.a105.zani.postclass.application.exception.PipelineJobNotFoundException;

/** 사후 처리 작업을 다음 단계로 옮긴다. 전사·분석·검증·공개 각 단계가 자기 일을 끝낼 때 부른다. */
public interface AdvancePipelineJobUseCase {

    /**
     * @throws PipelineJobNotFoundException 세션의 작업이 없음
     * @throws IllegalPipelineTransitionException 지금 단계에서 갈 수 없는 단계
     */
    AdvancePipelineJobResult advance(AdvancePipelineJobCommand command);
}
