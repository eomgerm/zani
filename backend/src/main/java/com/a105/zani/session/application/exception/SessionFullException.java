package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.BusinessException;

/** 수업 정원(강사 포함 30명)이 찼다. 이미 들어와 있는 참가자의 재입장은 정원을 다시 쓰지 않으므로 이 예외를 받지 않는다. */
public class SessionFullException extends BusinessException {

    public SessionFullException() {
        super(SessionApplicationErrorCode.SESSION_FULL);
    }
}
