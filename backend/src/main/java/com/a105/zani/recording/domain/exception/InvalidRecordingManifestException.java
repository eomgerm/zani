package com.a105.zani.recording.domain.exception;

import com.a105.zani.common.error.BusinessException;

public class InvalidRecordingManifestException extends BusinessException {

    public InvalidRecordingManifestException() {
        super(RecordingErrorCode.INVALID_RECORDING_MANIFEST);
    }
}
