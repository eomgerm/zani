package com.a105.zani.common.error.handler;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.a105.zani.common.error.ErrorType;

@Component
public class ErrorTypeHttpStatusMapper {

    public HttpStatus map(ErrorType type) {
        return switch (type) {
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case PAYLOAD_TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            case UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_SERVER_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
