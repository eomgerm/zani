package com.a105.zani.report.application.exception;

import com.a105.zani.common.error.BusinessException;

/** 강사 리포트가 아직 공개되지 않았다. 행이 없거나 {@code published_at} 이 비어 있는 경우다. */
public class InstructorReportNotReadyException extends BusinessException {

    public InstructorReportNotReadyException() {
        super(ReportErrorCode.INSTRUCTOR_REPORT_NOT_READY);
    }
}
