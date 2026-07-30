package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.BusinessException;

/** 강사가 아직 시작하지 않은 수업에 입장을 시도했다. 초대 코드는 시작 이후부터 유효하다. */
public class SessionNotStartedException extends BusinessException {

    public SessionNotStartedException() {
        super(SessionApplicationErrorCode.SESSION_NOT_STARTED);
    }
}
