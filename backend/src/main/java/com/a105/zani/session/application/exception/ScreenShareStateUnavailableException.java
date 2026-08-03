package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.BusinessException;

/** 활성 공유 상태 저장소(Redis) 장애를 애플리케이션 에러로 변환한다. */
public class ScreenShareStateUnavailableException extends BusinessException {

    public ScreenShareStateUnavailableException(Throwable cause) {
        super(SessionApplicationErrorCode.SCREEN_SHARE_STATE_UNAVAILABLE, cause);
    }
}
