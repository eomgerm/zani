package com.a105.zani.report.application.exception;

import com.a105.zani.common.error.BusinessException;

public class ReportNotReadyException extends BusinessException {

    public ReportNotReadyException() {
        super(ReportErrorCode.REPORT_NOT_READY);
    }
}
