package com.a105.zani.recording.domain.exception;

import com.a105.zani.common.error.BusinessException;

public class InvalidRecordingTrackException extends BusinessException {

    public InvalidRecordingTrackException() {
        super(RecordingErrorCode.INVALID_RECORDING_TRACK);
    }
}
