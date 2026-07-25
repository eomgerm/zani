package com.a105.zani.recording.application.exception;

import com.a105.zani.common.error.BusinessException;

public class WebhookEventStoreUnavailableException extends BusinessException {

    public WebhookEventStoreUnavailableException(Throwable cause) {
        super(RecordingWebhookErrorCode.WEBHOOK_EVENT_STORE_UNAVAILABLE, cause);
    }
}
