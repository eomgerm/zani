package com.a105.zani.session.domain.exception;

import com.a105.zani.common.error.BusinessException;

public class IllegalSessionTransitionException extends BusinessException {

    public IllegalSessionTransitionException() {
        super(SessionErrorCode.ILLEGAL_STATUS_TRANSITION);
    }
}
