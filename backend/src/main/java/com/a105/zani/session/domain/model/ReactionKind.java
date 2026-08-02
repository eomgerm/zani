package com.a105.zani.session.domain.model;

import java.util.Optional;

/**
 * 보낼 수 있는 반응 종류.
 *
 * <p><b>이모지 문자를 그대로 주고받지 않는 이유가 둘 있다.</b> 하나, 서버가 받은 문자열을 모든 참가자 화면에 그대로 띄우게 되므로 임의 문자열을 허용할 수 없다. 둘, 같은 하트라도 변이
 * 선택자(U+FE0F) 유무로 다른 문자열이 되어 리포트 집계가 갈라진다.
 *
 * <p>그림은 클라이언트가 고른다. 디자인이 이모지를 바꿔도 이미 쌓인 기록의 의미는 그대로다.
 */
public enum ReactionKind {
    LIKE,
    HEART,
    CLAP,
    CELEBRATE,
    WOW,
    CHEER;

    /** 클라이언트가 보낸 값을 해석한다. 모르는 값이면 비어 있다 — 화면에 띄울 수 없는 종류를 받아들이지 않는다. */
    public static Optional<ReactionKind> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (ReactionKind kind : values()) {
            if (kind.name().equalsIgnoreCase(value)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
