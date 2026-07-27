package com.a105.zani.audioclip.application.exception;

import com.a105.zani.common.error.BusinessException;

public class UnsupportedAudioFormatException extends BusinessException {

    public UnsupportedAudioFormatException() {
        super(AudioClipErrorCode.UNSUPPORTED_FORMAT);
    }
}
