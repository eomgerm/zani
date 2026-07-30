package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.BusinessException;

/** 이미 다른 참가자가 화면을 공유 중이라 새 공유를 거부한다("한 번에 하나", FRD §10.2). */
public class ScreenShareInUseException extends BusinessException {

    public ScreenShareInUseException() {
        super(SessionApplicationErrorCode.SCREEN_SHARE_IN_USE);
    }
}
