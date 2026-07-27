package com.a105.zani.recording.application.exception;

import com.a105.zani.common.error.BusinessException;

public class RecordingOutboxUnavailableException extends BusinessException {

    public RecordingOutboxUnavailableException(Throwable cause) {
        super(RecordingApplicationErrorCode.RECORDING_OUTBOX_UNAVAILABLE, cause);
    }
}
