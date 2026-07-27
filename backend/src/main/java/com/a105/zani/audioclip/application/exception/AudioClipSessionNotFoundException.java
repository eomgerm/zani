package com.a105.zani.audioclip.application.exception;

import com.a105.zani.common.error.BusinessException;

public class AudioClipSessionNotFoundException extends BusinessException {

    public AudioClipSessionNotFoundException() {
        super(AudioClipErrorCode.SESSION_NOT_FOUND);
    }
}
