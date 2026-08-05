package com.a105.zani.report.application.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum SessionAnalysisErrorCode implements ErrorCode {
    /** 다른 시도가 먼저 적재했다. 결과는 "이미 있다"와 같으므로 호출부는 실패로 다루지 않아도 된다. */
    SESSION_ANALYSIS_ALREADY_STORED(
            ErrorType.CONFLICT, "SESSION_ANALYSIS_001", "Session analysis has already been stored"),
    /** 구간이 계약을 지키지 않아 적재하지 않았다(겹침·역순·범위 밖·빈 제목 등). */
    INVALID_SESSION_ANALYSIS(ErrorType.BAD_REQUEST, "SESSION_ANALYSIS_002", "Invalid session analysis");

    private final ErrorType type;
    private final String code;
    private final String message;

    SessionAnalysisErrorCode(ErrorType type, String code, String message) {
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
