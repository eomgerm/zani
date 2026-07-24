package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.BusinessException;

public class LiveKitNotConfiguredException extends BusinessException {

    public LiveKitNotConfiguredException() {
        super(MediaTokenErrorCode.LIVEKIT_NOT_CONFIGURED);
    }
}
