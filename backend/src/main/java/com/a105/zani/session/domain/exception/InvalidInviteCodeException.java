package com.a105.zani.session.domain.exception;

import com.a105.zani.common.error.BusinessException;

/** 정규화해도 대문자 영숫자 8자가 되지 않는 초대 코드다. */
public class InvalidInviteCodeException extends BusinessException {

    public InvalidInviteCodeException() {
        super(SessionErrorCode.INVALID_INVITE_CODE);
    }
}
