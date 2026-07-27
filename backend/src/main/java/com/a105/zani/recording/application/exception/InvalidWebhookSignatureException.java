package com.a105.zani.recording.application.exception;

import com.a105.zani.common.error.BusinessException;

public class InvalidWebhookSignatureException extends BusinessException {

    public InvalidWebhookSignatureException(Throwable cause) {
        super(RecordingWebhookErrorCode.INVALID_WEBHOOK_SIGNATURE, cause);
    }
}
