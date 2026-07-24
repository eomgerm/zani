package com.a105.zani.member.domain.exception;

import com.a105.zani.common.error.BusinessException;

public class InvalidGoogleIdentityException extends BusinessException {

    public InvalidGoogleIdentityException() {
        super(MemberErrorCode.INVALID_GOOGLE_IDENTITY);
    }
}
