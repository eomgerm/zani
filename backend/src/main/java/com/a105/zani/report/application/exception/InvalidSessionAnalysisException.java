package com.a105.zani.report.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 분석 결과가 구간 계약을 지키지 않아 적재하지 않았다. 어떤 규칙을 어겼는지는 원인 예외가 담는다.
 *
 * <p>도메인 예외를 그대로 올리지 않고 이 타입으로 바꿔 내보낸다 — 분석을 수행하는 {@code postclass} 는 {@code report} 의 애플리케이션 경계만 알아야 한다.
 */
public class InvalidSessionAnalysisException extends BusinessException {

    public InvalidSessionAnalysisException(Throwable cause) {
        super(SessionAnalysisErrorCode.INVALID_SESSION_ANALYSIS, cause);
    }
}
