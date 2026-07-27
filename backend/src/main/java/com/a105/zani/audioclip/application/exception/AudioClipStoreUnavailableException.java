package com.a105.zani.audioclip.application.exception;

import com.a105.zani.common.error.BusinessException;

public class AudioClipStoreUnavailableException extends BusinessException {

    public AudioClipStoreUnavailableException(Throwable cause) {
        super(AudioClipErrorCode.STORE_UNAVAILABLE, cause);
    }
}
