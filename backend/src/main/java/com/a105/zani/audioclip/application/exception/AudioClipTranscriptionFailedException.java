package com.a105.zani.audioclip.application.exception;

import com.a105.zani.common.error.BusinessException;

public class AudioClipTranscriptionFailedException extends BusinessException {

    public AudioClipTranscriptionFailedException(Throwable cause) {
        super(AudioClipErrorCode.TRANSCRIPTION_FAILED, cause);
    }
}
