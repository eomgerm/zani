package com.a105.zani.postclass.application.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum ContentAnalysisErrorCode implements ErrorCode {
    TRANSCRIPT_NOT_READY(ErrorType.CONFLICT, "CONTENT_ANALYSIS_001", "Session transcript is not ready"),
    CONTENT_ANALYSIS_UNAVAILABLE(ErrorType.SERVICE_UNAVAILABLE, "CONTENT_ANALYSIS_002", "Content analysis failed"),
    /** 응답이 왔지만 쓸 수 없다. 같은 요청을 다시 보내도 같으므로 재시도 대상이 아니다. */
    CONTENT_ANALYSIS_UNUSABLE_RESPONSE(
            ErrorType.SERVICE_UNAVAILABLE, "CONTENT_ANALYSIS_003", "Content analysis returned an unusable response");

    private final ErrorType type;
    private final String code;
    private final String message;

    ContentAnalysisErrorCode(ErrorType type, String code, String message) {
        this.type = type;
        this.code = code;
        this.message = message;
    }

    @Override
    public ErrorType type() {
        return type;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
