package com.a105.zani.report.domain.exception;

import com.a105.zani.common.error.BusinessException;

/** 개인 리포트나 그 복습 추천이 자기 규칙을 어겼다. 사유는 {@link StudentReportErrorCode} 가 나눈다 — 오류마다 예외 클래스를 만들지 않는다. */
public class InvalidStudentReportException extends BusinessException {

    public InvalidStudentReportException(StudentReportErrorCode errorCode) {
        super(errorCode);
    }
}
