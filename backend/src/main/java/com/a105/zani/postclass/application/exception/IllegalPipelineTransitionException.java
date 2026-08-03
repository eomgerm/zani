package com.a105.zani.postclass.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 사후 처리 작업을 지금 단계에서 갈 수 없는 단계로 옮기려 했다.
 *
 * <p>단계를 건너뛰었거나, 이미 끝난 작업(PUBLISHED·FAILED)을 다시 움직이려 한 경우다. 어느 쪽이든 앞 단계의 산출물 없이 다음 단계가 도는 것을 막아야 하므로 거절한다 — 같은 단계를 다시
 * 보고하는 중복 호출은 여기로 오지 않고 멱등 성공으로 처리된다.
 */
public class IllegalPipelineTransitionException extends BusinessException {

    public IllegalPipelineTransitionException() {
        super(PostClassJobErrorCode.ILLEGAL_PIPELINE_TRANSITION);
    }
}
