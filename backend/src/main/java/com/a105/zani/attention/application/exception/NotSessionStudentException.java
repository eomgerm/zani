package com.a105.zani.attention.application.exception;

import com.a105.zani.common.error.BusinessException;

/** 학생이 아닌 참가자가 판정 이벤트를 보냈다. 참여도 판정은 학생만 만든다(확정 문서 §1). */
public class NotSessionStudentException extends BusinessException {

    public NotSessionStudentException() {
        super(AttentionEventErrorCode.NOT_SESSION_STUDENT);
    }
}
