package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.BusinessException;

public class SessionPresenceUnavailableException extends BusinessException {

    public SessionPresenceUnavailableException(Throwable cause) {
        super(SessionPresenceErrorCode.PRESENCE_STORE_UNAVAILABLE, cause);
    }
}
