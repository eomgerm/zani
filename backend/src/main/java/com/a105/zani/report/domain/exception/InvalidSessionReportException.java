package com.a105.zani.report.domain.exception;

import com.a105.zani.common.error.BusinessException;

public class InvalidSessionReportException extends BusinessException {

    public InvalidSessionReportException(SessionReportErrorCode errorCode) {
        super(errorCode);
    }
}
