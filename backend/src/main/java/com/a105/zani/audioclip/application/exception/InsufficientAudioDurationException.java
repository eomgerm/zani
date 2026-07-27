package com.a105.zani.audioclip.application.exception;

import com.a105.zani.common.error.BusinessException;

public class InsufficientAudioDurationException extends BusinessException {

    public InsufficientAudioDurationException() {
        super(AudioClipErrorCode.INSUFFICIENT_DURATION);
    }
}
