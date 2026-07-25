package com.a105.zani.recording.domain.exception;

import com.a105.zani.common.error.BusinessException;

public class ForbiddenStudentCameraTrackException extends BusinessException {

    public ForbiddenStudentCameraTrackException() {
        super(RecordingErrorCode.FORBIDDEN_STUDENT_CAMERA_TRACK);
    }
}
