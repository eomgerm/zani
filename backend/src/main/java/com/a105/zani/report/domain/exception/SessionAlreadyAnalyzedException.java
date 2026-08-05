package com.a105.zani.report.domain.exception;

import com.a105.zani.common.error.BusinessException;

/** 이 세션의 공통 리포트가 이미 있다. 존재 확인과 저장 사이에 다른 시도가 끼어들어 UK_SESSION_REPORTS_SESSION 이 한쪽을 막은 경우다. */
public class SessionAlreadyAnalyzedException extends BusinessException {

    public SessionAlreadyAnalyzedException() {
        super(SessionReportErrorCode.SESSION_ALREADY_ANALYZED);
    }
}
