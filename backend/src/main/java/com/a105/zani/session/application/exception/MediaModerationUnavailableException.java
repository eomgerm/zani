package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 미디어 서버를 쓰지 못해 음소거하지 못했다.
 *
 * <p>성공으로 돌려주지 않는 이유: 강사 화면에는 음소거로 보이는데 학생 소리는 계속 나가는 상태가 된다. 실패를 알려 강사가 다시 시도하거나 말로 요청할 수 있게 한다.
 */
public class MediaModerationUnavailableException extends BusinessException {

    public MediaModerationUnavailableException() {
        super(SessionApplicationErrorCode.MEDIA_MODERATION_UNAVAILABLE);
    }
}
