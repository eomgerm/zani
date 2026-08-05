package com.a105.zani.recording.application.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum MediaAccessErrorCode implements ErrorCode {
    // 위조·만료를 구분하지 않는다. 클라이언트가 할 일은 둘 다 "주소 재발급"이고, 구분해 주면 유효한 서명을 찾았는지 알려주는 꼴이 된다.
    INVALID_MEDIA_ACCESS(
            ErrorType.UNAUTHORIZED, "MEDIA_ACCESS_001", "The media access credential is invalid or has expired"),
    // 진행 중 수업과 병합 미완을 구분하지 않는다 — 참여자에게는 둘 다 "아직 볼 수 없음"이다.
    MEDIA_NOT_READY(ErrorType.NOT_FOUND, "MEDIA_ACCESS_002", "The lecture recording for this session is not ready yet");

    private final ErrorType type;
    private final String code;
    private final String message;

    MediaAccessErrorCode(ErrorType type, String code, String message) {
        this.type = type;
        this.code = code;
        this.message = message;
    }

    @Override
    public ErrorType type() {
        return type;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
