package com.a105.zani.recording.domain.exception;

import com.a105.zani.common.error.BusinessException;

public class InvalidRecordingAliasException extends BusinessException {

    public InvalidRecordingAliasException() {
        super(RecordingErrorCode.INVALID_RECORDING_ALIAS);
    }
}
