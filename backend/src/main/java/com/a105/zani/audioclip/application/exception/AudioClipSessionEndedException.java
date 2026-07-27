package com.a105.zani.audioclip.application.exception;

import com.a105.zani.common.error.BusinessException;

public class AudioClipSessionEndedException extends BusinessException {

    public AudioClipSessionEndedException() {
        super(AudioClipErrorCode.SESSION_ALREADY_ENDED);
    }
}
