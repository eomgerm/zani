package com.a105.zani.recording.application.exception;

import com.a105.zani.common.error.BusinessException;

public class TrackEgressUnavailableException extends BusinessException {

    public TrackEgressUnavailableException(Throwable cause) {
        super(RecordingApplicationErrorCode.TRACK_EGRESS_UNAVAILABLE, cause);
    }
}
