package com.a105.zani.session.infrastructure.websocket;

import java.util.Optional;

/**
 * STOMP 목적지 규격. 설정·인터셉터·발행 어댑터가 같은 문자열을 쓰도록 한곳에 모은다.
 *
 * <p>업무 이벤트는 종류별로 주제를 나누지 않고 <b>세션당 하나</b>를 쓴다. 채팅·손들기·반응을 각각 다른 주제로 두면 구독·재연결·순서 보장이 셋으로 늘어나는데, 어차피 같은 수업 화면이 전부 소비하므로
 * 나눌 이득이 없다. 종류는 봉투의 {@code type} 으로 구분한다.
 */
public final class SessionChannelDestinations {

    /** WebSocket 핸드셰이크 경로. SecurityConfig 의 permitAll 목록과 함께 바뀌어야 한다. */
    public static final String HANDSHAKE_PATH = "/ws";

    /** 브로커가 구독을 받는 접두사. */
    public static final String BROKER_PREFIX = "/topic";

    /** 클라이언트가 서버 핸들러로 보낼 때 쓰는 접두사({@code @MessageMapping}). */
    public static final String APPLICATION_PREFIX = "/app";

    /** 보낸 사람에게만 되돌려 주는 목적지 접두사(전송 거절 통지). */
    public static final String USER_PREFIX = "/user";

    /** 전송이 거절됐을 때 보낸 사람에게만 알리는 큐. */
    public static final String ERROR_QUEUE = "/queue/errors";

    private static final String SESSION_TOPIC_PREFIX = BROKER_PREFIX + "/sessions/";

    private SessionChannelDestinations() {}

    public static String sessionTopic(long sessionId) {
        return SESSION_TOPIC_PREFIX + sessionId;
    }

    /**
     * 목적지가 세션 주제면 세션 ID 를, 아니면 빈 값을 준다. 세션 주제가 아닌 구독(오류 큐 등)은 멤버십 검사 대상이 아니다.
     *
     * <p>숫자가 아닌 꼬리는 빈 값으로 취급한다 — 구독 목적지는 클라이언트가 보내는 값이라 임의 문자열이 올 수 있다.
     */
    public static Optional<Long> sessionIdOf(String destination) {
        if (destination == null || !destination.startsWith(SESSION_TOPIC_PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(destination.substring(SESSION_TOPIC_PREFIX.length())));
        } catch (NumberFormatException notASessionTopic) {
            return Optional.empty();
        }
    }
}
