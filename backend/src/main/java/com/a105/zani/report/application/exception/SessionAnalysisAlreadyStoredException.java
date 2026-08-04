package com.a105.zani.report.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 이 세션의 공통 분석이 이미 적재돼 있다. 존재 확인과 저장 사이에 다른 시도가 끼어들어 유니크 제약이 이쪽을 막은 경우다.
 *
 * <p>도메인 예외를 그대로 올리지 않고 이 타입으로 바꿔 내보낸다 — 분석을 수행하는 {@code postclass} 는 {@code report} 의 애플리케이션 경계만 알아야 한다.
 */
public class SessionAnalysisAlreadyStoredException extends BusinessException {

    public SessionAnalysisAlreadyStoredException(Throwable cause) {
        super(SessionAnalysisErrorCode.SESSION_ANALYSIS_ALREADY_STORED, cause);
    }
}
