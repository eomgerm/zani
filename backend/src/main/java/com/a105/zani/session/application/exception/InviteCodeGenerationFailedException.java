package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.BusinessException;

public class InviteCodeGenerationFailedException extends BusinessException {

    public InviteCodeGenerationFailedException() {
        super(SessionApplicationErrorCode.INVITE_CODE_GENERATION_FAILED);
    }
}
