package com.a105.zani.session.infrastructure.websocket;

import java.security.Principal;

/**
 * STOMP 연결의 인증 주체. {@code name} 은 회원 ID 문자열로, HTTP 쪽에서 {@code jwt.getSubject()} 가 주는 값과 같다 — 두 경로가 같은 형태를 쓰면 핸들러가 주체를
 * 해석하는 방식이 갈리지 않는다.
 */
public record StompPrincipal(String name) implements Principal {

    @Override
    public String getName() {
        return name;
    }
}
