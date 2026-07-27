package com.a105.zani.session.infrastructure.livekit;

import org.springframework.stereotype.Component;

import com.a105.zani.session.application.port.MediaRoomPort;
import com.a105.zani.session.application.port.MediaServerCredentials;

/** 세션이 소유한 LiveKit room 이름 규칙·접속 설정을 application port로 공개하는 어댑터. 벤더 설정 타입은 세션 인프라 안에만 머문다. */
@Component
public class LiveKitMediaRoomAdapter implements MediaRoomPort {

    private final LiveKitProperties properties;

    public LiveKitMediaRoomAdapter(LiveKitProperties properties) {
        this.properties = properties;
    }

    @Override
    public String roomName(Long sessionId) {
        return LiveKitRoomNames.sessionRoom(properties.environment(), sessionId);
    }

    @Override
    public java.util.Optional<Long> resolveSessionId(String roomName) {
        return LiveKitRoomNames.parseSessionId(properties.environment(), roomName);
    }

    @Override
    public MediaServerCredentials credentials() {
        return new MediaServerCredentials(properties.url(), properties.apiKey(), properties.apiSecret());
    }
}
