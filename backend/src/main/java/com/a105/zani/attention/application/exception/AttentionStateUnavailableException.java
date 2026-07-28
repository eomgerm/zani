package com.a105.zani.attention.application.exception;

import com.a105.zani.common.error.BusinessException;

/** 판정 상태 저장소(Redis) 장애. 코칭은 멈추되 수업 자체는 계속된다. */
public class AttentionStateUnavailableException extends BusinessException {

    public AttentionStateUnavailableException(Throwable cause) {
        super(AttentionStateErrorCode.ATTENTION_STORE_UNAVAILABLE, cause);
    }
}
