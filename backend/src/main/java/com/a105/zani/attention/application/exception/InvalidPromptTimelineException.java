package com.a105.zani.attention.application.exception;

import com.a105.zani.common.error.BusinessException;

/** 프롬프트 표시·응답 시각이 수업 시간선과 어긋난다. 그대로 두면 오프셋이 0으로 눌려 다른 프롬프트와 같은 것으로 잡힌다. */
public class InvalidPromptTimelineException extends BusinessException {

    public InvalidPromptTimelineException() {
        super(PromptResponseErrorCode.INVALID_PROMPT_TIMELINE);
    }
}
