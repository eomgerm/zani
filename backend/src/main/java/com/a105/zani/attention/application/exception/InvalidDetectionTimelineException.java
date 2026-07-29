package com.a105.zani.attention.application.exception;

import com.a105.zani.common.error.BusinessException;

/** 관측 시각이 수업 시간선과 어긋난다. 눌러 담으면 판정 순서 판단이 무너진다. */
public class InvalidDetectionTimelineException extends BusinessException {

    public InvalidDetectionTimelineException() {
        super(AttentionEventErrorCode.INVALID_DETECTION_TIMELINE);
    }
}
