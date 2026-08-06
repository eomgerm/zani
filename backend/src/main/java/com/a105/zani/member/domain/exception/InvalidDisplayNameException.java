package com.a105.zani.member.domain.exception;

import com.a105.zani.common.error.BusinessException;

public class InvalidDisplayNameException extends BusinessException {

    public InvalidDisplayNameException() {
        super(MemberErrorCode.INVALID_DISPLAY_NAME);
    }
}
