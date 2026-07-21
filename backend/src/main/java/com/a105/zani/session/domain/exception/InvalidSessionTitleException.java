package com.a105.zani.session.domain.exception;

import com.a105.zani.common.error.BusinessException;

public class InvalidSessionTitleException extends BusinessException {

    public InvalidSessionTitleException() {
        super(SessionErrorCode.INVALID_TITLE);
    }
}
