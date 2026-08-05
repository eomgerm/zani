package com.a105.zani.report.application.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum ReportErrorCode implements ErrorCode {

    /**
     * 아직 만들어지는 중이다.
     *
     * <p>404 가 아니라 409 인 이유: 없는 것이 아니라 <b>아직인</b> 것이다. 404 로 내리면 화면이 "리포트가 없는 수업" 으로 다루고 다시 열어 볼 이유를 잃는다. 409 는 "지금은 안
     * 되지만 나중에 된다" 를 뜻한다.
     */
    INSTRUCTOR_REPORT_NOT_READY(ErrorType.CONFLICT, "REPORT_001", "The instructor report has not been published yet");

    private final ErrorType type;
    private final String code;
    private final String message;

    ReportErrorCode(ErrorType type, String code, String message) {
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
