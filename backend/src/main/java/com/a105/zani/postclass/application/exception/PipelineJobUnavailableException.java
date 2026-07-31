package com.a105.zani.postclass.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 사후 처리 작업 저장소에 쓸 수 없다.
 *
 * <p>확정을 되돌린다 — 확정만 남고 작업이 없으면 분석이 시작되지 않는데, 확정은 다시 할 수 없어 그 수업은 영구히 멈춘다. 실패로 돌려주면 강사가 다시 완료를 누르거나 30분 비활성 확정이 다시 시도한다.
 */
public class PipelineJobUnavailableException extends BusinessException {

    public PipelineJobUnavailableException(Throwable cause) {
        super(PostClassJobErrorCode.PIPELINE_JOB_UNAVAILABLE, cause);
    }
}
