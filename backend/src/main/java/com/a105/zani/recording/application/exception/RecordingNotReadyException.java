package com.a105.zani.recording.application.exception;

import com.a105.zani.common.error.BusinessException;

/** egress 이벤트가 recordings 행 커밋보다 먼저 도착했다. 5xx로 응답해 LiveKit 재전송에서 재처리되게 한다. */
public class RecordingNotReadyException extends BusinessException {

    public RecordingNotReadyException() {
        super(RecordingWebhookErrorCode.RECORDING_NOT_READY);
    }
}
