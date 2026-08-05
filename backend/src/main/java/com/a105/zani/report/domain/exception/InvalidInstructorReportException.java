package com.a105.zani.report.domain.exception;

import com.a105.zani.common.error.BusinessException;

/** 강사 리포트나 그 인사이트가 자기 규칙을 어겼다. 부분 저장은 없다 — 한 트랜잭션이라 함께 롤백된다. */
public class InvalidInstructorReportException extends BusinessException {

    public InvalidInstructorReportException(InstructorReportErrorCode errorCode) {
        super(errorCode);
    }
}
