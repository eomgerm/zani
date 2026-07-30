package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 이미 끝난 수업에 입장을 시도했다.
 *
 * <p>{@link SessionAlreadyEndedException} 과 뜻은 같지만 그쪽은 미디어 토큰 발급 경로의 코드({@code MEDIA_TOKEN_003})를 쓴다. 입장 실패를 미디어 토큰 오류로
 * 보고하면 클라이언트가 원인을 잘못 짚으므로 코드를 나눈다.
 */
public class SessionEndedException extends BusinessException {

    public SessionEndedException() {
        super(SessionApplicationErrorCode.SESSION_ENDED);
    }
}
