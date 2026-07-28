package com.a105.zani.member.application.exception;

import com.a105.zani.common.error.BusinessException;

public class MemberNotFoundException extends BusinessException {

    public MemberNotFoundException() {
        super(MemberApplicationErrorCode.MEMBER_NOT_FOUND);
    }
}
