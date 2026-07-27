package com.a105.zani.audioclip.application.exception;

import com.a105.zani.common.error.BusinessException;

public class AudioClipRequestAlreadyResolvedException extends BusinessException {

    public AudioClipRequestAlreadyResolvedException() {
        super(AudioClipErrorCode.REQUEST_ALREADY_RESOLVED);
    }
}
