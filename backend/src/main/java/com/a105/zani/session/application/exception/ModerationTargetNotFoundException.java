package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.BusinessException;

public class ModerationTargetNotFoundException extends BusinessException {

    public ModerationTargetNotFoundException() {
        super(SessionApplicationErrorCode.MODERATION_TARGET_NOT_FOUND);
    }
}
