package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.BusinessException;

public class SessionCapacityReachedException extends BusinessException {

    public SessionCapacityReachedException() {
        super(SessionApplicationErrorCode.SESSION_CAPACITY_REACHED);
    }
}
