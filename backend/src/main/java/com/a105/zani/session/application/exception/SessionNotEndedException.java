package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 아직 진행 중인 세션에 종료 후에만 되는 일을 요청했다.
 *
 * <p>{@link SessionAlreadyEndedException} 의 반대다. 저쪽은 끝난 수업에 실시간 동작을 요청한 경우고, 이쪽은 아직 안 끝난 수업에 사후 리포트를 요청한 경우다.
 */
public class SessionNotEndedException extends BusinessException {

    public SessionNotEndedException() {
        super(SessionApplicationErrorCode.SESSION_NOT_ENDED);
    }
}
