package com.a105.zani.attention.application.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

/**
 * 리포트 타임라인 조회의 애플리케이션 오류.
 *
 * <p>{@link AttentionEventErrorCode} 와 나눠 둔다. 저쪽은 수업 중 판정을 보내는 경로의 오류라 같은 "학생이 아님"이라도 메시지가 다르고, 한 enum 에 섞으면 어느 쪽 경로에서
 * 난 오류인지 코드만 보고는 알 수 없다.
 */
public enum AttentionTimelineErrorCode implements ErrorCode {
    NOT_SESSION_STUDENT_TIMELINE(
            ErrorType.FORBIDDEN,
            "ATTENTION_TIMELINE_001",
            "Only session students can read a personal attention timeline");

    private final ErrorType type;
    private final String code;
    private final String message;

    AttentionTimelineErrorCode(ErrorType type, String code, String message) {
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
