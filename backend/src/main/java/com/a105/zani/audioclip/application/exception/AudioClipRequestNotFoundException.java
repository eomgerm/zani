package com.a105.zani.audioclip.application.exception;

import com.a105.zani.common.error.BusinessException;

public class AudioClipRequestNotFoundException extends BusinessException {

    public AudioClipRequestNotFoundException() {
        super(AudioClipErrorCode.REQUEST_NOT_FOUND);
    }
}
