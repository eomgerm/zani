package com.a105.zani.audioclip.application.exception;

import com.a105.zani.common.error.BusinessException;

public class AudioClipRequestExpiredException extends BusinessException {

    public AudioClipRequestExpiredException() {
        super(AudioClipErrorCode.REQUEST_EXPIRED);
    }
}
