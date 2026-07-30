package com.a105.zani.session.domain.exception;

import com.a105.zani.common.error.BusinessException;

/** 진행 중인 세션에만 허용되는 전이를 이미 종료 절차에 들어간 세션에 요청했다. 생명주기는 역행하지 않는다. */
public class SessionNotLiveException extends BusinessException {

    public SessionNotLiveException() {
        super(SessionErrorCode.SESSION_NOT_LIVE);
    }
}
