package com.a105.zani.common.error;

public enum ErrorType {
    BAD_REQUEST,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    /** 호출 빈도 제한. 클라이언트가 재시도 간격을 스스로 늘릴 수 있도록 429 로 내린다 — 409 로 뭉뚱그리면 상태코드만으로는 백오프할지 판단할 수 없다. */
    TOO_MANY_REQUESTS,
    SERVICE_UNAVAILABLE,
    INTERNAL_SERVER_ERROR
}
