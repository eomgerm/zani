package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.BusinessException;

public class ModerationTargetNotStudentException extends BusinessException {

    public ModerationTargetNotStudentException() {
        super(SessionApplicationErrorCode.MODERATION_TARGET_NOT_STUDENT);
    }
}
