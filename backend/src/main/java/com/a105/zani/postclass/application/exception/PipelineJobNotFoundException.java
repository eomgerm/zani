package com.a105.zani.postclass.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 세션의 사후 처리 작업이 없다.
 *
 * <p>작업은 메모 확정과 같은 트랜잭션에서만 만들어진다(FRD §16 NOTE-004). 따라서 없다는 것은 아직 확정되지 않은 세션이거나 세션 ID 가 틀린 것이지, 단계를 옮길 수 있는 상태가 아니다.
 */
public class PipelineJobNotFoundException extends BusinessException {

    public PipelineJobNotFoundException() {
        super(PostClassJobErrorCode.PIPELINE_JOB_NOT_FOUND);
    }
}
