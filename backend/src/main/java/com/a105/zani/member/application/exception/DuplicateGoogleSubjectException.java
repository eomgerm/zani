package com.a105.zani.member.application.exception;

import com.a105.zani.common.error.BusinessException;

public class DuplicateGoogleSubjectException extends BusinessException {

    public DuplicateGoogleSubjectException(Throwable cause) {
        super(MemberApplicationErrorCode.DUPLICATE_GOOGLE_SUBJECT, cause);
    }
}
