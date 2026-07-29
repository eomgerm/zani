package com.a105.zani.session.domain.exception;

import com.a105.zani.common.error.BusinessException;

public class InvalidInviteCodeException extends BusinessException {

    public InvalidInviteCodeException() {
        super(SessionErrorCode.INVALID_INVITE_CODE);
    }
}
