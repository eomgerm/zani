package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.BusinessException;

/** 사후 경로(메모·리포트)는 수업이 끝난 뒤에만 열린다. 아직 진행 중인 세션에는 적용할 수 없다. */
public class SessionNotEndedException extends BusinessException {

    public SessionNotEndedException() {
        super(SessionApplicationErrorCode.SESSION_NOT_ENDED);
    }
}
