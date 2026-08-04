package com.a105.zani.report.application.exception;

import com.a105.zani.common.error.BusinessException;

public class NotSessionStudentReportException extends BusinessException {

    public NotSessionStudentReportException() {
        super(ReportErrorCode.NOT_SESSION_STUDENT);
    }
}
