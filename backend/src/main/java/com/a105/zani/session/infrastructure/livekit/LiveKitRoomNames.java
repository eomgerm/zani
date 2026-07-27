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

    /**
     * room 이름에서 세션 ID를 복원한다. 환경 세그먼트까지 왕복 검증해, 같은 LiveKit 인스턴스를 공유하는 다른 환경의 room이 이 배포의 세션으로 오인되지 않게 한다. 규칙에 맞지 않으면 비어
     * 있다.
     */
    public static java.util.Optional<Long> parseSessionId(String environment, String roomName) {
        if (roomName == null) {
            return java.util.Optional.empty();
        }
        int marker = roomName.lastIndexOf("-session-");
        if (marker < 0) {
            return java.util.Optional.empty();
        }
        try {
            long sessionId = Long.parseLong(roomName.substring(marker + "-session-".length()));
            return roomName.equals(sessionRoom(environment, sessionId))
                    ? java.util.Optional.of(sessionId)
                    : java.util.Optional.empty();
        } catch (NumberFormatException invalid) {
            return java.util.Optional.empty();
        }
    }
}
