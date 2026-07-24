package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.BusinessException;

public class MediaTokenSessionNotFoundException extends BusinessException {

    public MediaTokenSessionNotFoundException() {
        super(MediaTokenErrorCode.SESSION_NOT_FOUND);
    }
}
