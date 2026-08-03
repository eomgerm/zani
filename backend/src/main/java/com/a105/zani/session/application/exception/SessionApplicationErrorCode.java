package com.a105.zani.session.application.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum SessionApplicationErrorCode implements ErrorCode {
    ACTIVE_SESSION_EXISTS(ErrorType.CONFLICT, "SESSION_APP_001", "An active session already exists"),
    ACTIVATION_LOCK_UNAVAILABLE(
            ErrorType.SERVICE_UNAVAILABLE, "SESSION_APP_002", "Session activation lock store is unavailable"),
    DUPLICATE_INVITE_CODE(ErrorType.CONFLICT, "SESSION_APP_003", "Invite code already in use"),
    INVITE_CODE_GENERATION_FAILED(
            ErrorType.INTERNAL_SERVER_ERROR, "SESSION_APP_004", "Failed to generate a unique invite code"),
    SESSION_NOT_FOUND(ErrorType.NOT_FOUND, "SESSION_APP_005", "No session found"),
    // 종료 외에도 코칭 팁 폴링·사후 메모가 같은 인가를 쓴다. 특정 행위를 문구에 박으면 다른 응답에서 사실과 달라진다.
    NOT_SESSION_INSTRUCTOR(
            ErrorType.FORBIDDEN, "SESSION_APP_006", "Only the instructor of this session can perform this action"),
    SCREEN_SHARE_IN_USE(ErrorType.CONFLICT, "SESSION_APP_007", "Another participant is already sharing their screen"),
    SCREEN_SHARE_STATE_UNAVAILABLE(
            ErrorType.SERVICE_UNAVAILABLE, "SESSION_APP_008", "Screen share state store is unavailable"),
    SESSION_NOT_ENDED(ErrorType.CONFLICT, "SESSION_APP_009", "The session is still in progress"),
    MODERATION_TARGET_NOT_FOUND(ErrorType.NOT_FOUND, "SESSION_APP_010", "No such participant in this session"),
    // 강사끼리 서로 음소거하는 상황을 막는다. 강제 해제가 없어 상대가 스스로 켜기 전까지 수업이 멎는다.
    MODERATION_TARGET_NOT_STUDENT(
            ErrorType.FORBIDDEN, "SESSION_APP_011", "Only students can be muted by the instructor"),
    MEDIA_MODERATION_UNAVAILABLE(ErrorType.SERVICE_UNAVAILABLE, "SESSION_APP_012", "Media server is unavailable");

    private final ErrorType type;
    private final String code;
    private final String message;

    SessionApplicationErrorCode(ErrorType type, String code, String message) {
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
