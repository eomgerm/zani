package com.a105.zani.session.application.port;

/**
 * 세션의 미디어 room 을 서버가 닫는 포트. 이름·자격증명만 다루는 {@link MediaRoomPort} 와 달리 미디어 서버에 실제로 명령을 보낸다.
 *
 * <p>수업이 끝나면 room 도 닫아야 한다. 닫지 않으면 이미 발급된 토큰의 TTL(10분) 이 남아 있는 동안 종료된 수업에 다시 들어갈 수 있고, 아무도 없는 room 이 미디어 서버에 계속 남는다.
 */
public interface MediaRoomControlPort {

    /**
     * 세션의 room 을 닫고 남은 참가자를 모두 내보낸다.
     *
     * <p>이미 없는 room 은 성공으로 본다 — 종료는 여러 경로(강사 명시 종료·3시간·강사 미복귀)가 지나가고 재시도도 되므로, 없는 것을 실패로 만들면 멱등이 깨진다.
     *
     * @return 닫혔으면(또는 이미 없었으면) {@code true}, 미디어 서버를 쓰지 못해 확인할 수 없으면 {@code false}
     */
    boolean closeRoom(long sessionId);
}
