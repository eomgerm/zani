package com.a105.zani.recording.application.exception;

import com.a105.zani.common.error.BusinessException;

/** 녹화 주소의 서명이 맞지 않거나 유효 기간이 지났다(401). 클라이언트는 주소를 다시 발급받아 이어 재생한다. */
public class InvalidMediaAccessException extends BusinessException {

    public InvalidMediaAccessException() {
        super(MediaAccessErrorCode.INVALID_MEDIA_ACCESS);
    }
}
