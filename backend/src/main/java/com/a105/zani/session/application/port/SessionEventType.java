package com.a105.zani.session.application.port;

/**
 * 업무 이벤트 종류. 세션 주제 하나로 모든 이벤트가 나가므로 클라이언트는 이 값으로 갈라 처리한다.
 *
 * <p>손들기·반응(64), 화면 공유 상태(65), 강제 음소거(66)가 각자의 값을 여기에 추가한다.
 */
public enum SessionEventType {
    CHAT_MESSAGE,
    HAND_RAISED,
    HAND_LOWERED,
    REACTION
}
