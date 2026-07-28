package com.a105.zani.attention.application.exception;

import com.a105.zani.common.error.BusinessException;

/** 프롬프트 종류에 없는 답이 왔다(예: 자세 안내에 "헷갈려요"). 집계가 뒤틀리므로 거절한다. */
public class MismatchedPromptAnswerException extends BusinessException {

    public MismatchedPromptAnswerException() {
        super(PromptResponseErrorCode.MISMATCHED_ANSWER);
    }
}
