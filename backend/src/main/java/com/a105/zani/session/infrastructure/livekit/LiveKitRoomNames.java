package com.a105.zani.session.infrastructure.livekit;

/**
 * LiveKit room 이름 규칙의 단일 소유자: {@code zani-{environment}-session-{sessionId}}. 토큰 발급과 Egress 시작이 같은 규칙을 공유해야 하므로 세션(소유
 * 도메인) 인프라에 둔다. 규칙 변경은 반드시 이 클래스에서만 한다.
 */
public final class LiveKitRoomNames {

    private LiveKitRoomNames() {}

    public static String sessionRoom(String environment, Long sessionId) {
        return "zani-" + environment + "-session-" + sessionId;
    }
}
