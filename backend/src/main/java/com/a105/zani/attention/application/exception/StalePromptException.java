package com.a105.zani.attention.application.exception;

import com.a105.zani.common.error.BusinessException;

/** 답하기에는 너무 오래된 프롬프트다. 클라이언트는 재전송을 멈춘다. */
public class StalePromptException extends BusinessException {

    public StalePromptException() {
        super(PromptResponseErrorCode.STALE_PROMPT);
    }
}
