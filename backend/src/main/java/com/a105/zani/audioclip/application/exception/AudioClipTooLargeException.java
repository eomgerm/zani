package com.a105.zani.audioclip.application.exception;

import com.a105.zani.common.error.BusinessException;

public class AudioClipTooLargeException extends BusinessException {

    public AudioClipTooLargeException() {
        super(AudioClipErrorCode.CLIP_TOO_LARGE);
    }
}
