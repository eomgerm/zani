package com.a105.zani.session.application.port;

import java.util.Optional;

/**
 * 같은 {@code clientEventId} 로 두 번 들어온 전송을 한 번만 처리하기 위한 기록.
 *
 * <p>{@code chat_messages} 에 {@code clientEventId} 컬럼이 없어 DB 유니크 제약으로는 막을 수 없다. 스키마 변경 없이(티켓 63 제약) 처리해야 하므로 짧은 TTL 로
 * Redis 에 둔다 — 재시도는 몇 초 안에 오므로 오래 보관할 이유가 없다.
 */
public interface ChatIdempotencyPort {

    /**
     * {@code clientEventId} 를 이 {@code eventId} 로 선점한다.
     *
     * @return 처음 보는 값이면 빈 값. 이미 처리된 값이면 그때 부여했던 {@code eventId}
     */
    Optional<String> claim(long sessionId, String clientEventId, String eventId);

    /** 선점 후 저장이 실패했을 때 되돌린다. 놔두면 같은 재시도가 영원히 중복으로 걸러져 메시지가 사라진다. */
    void release(long sessionId, String clientEventId);
}
