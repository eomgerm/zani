package com.a105.zani.session.infrastructure.livekit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishData;
import io.livekit.server.CanSubscribe;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import io.livekit.server.VideoGrant;
import org.springframework.stereotype.Component;

import com.a105.zani.session.application.port.IssuedMediaToken;
import com.a105.zani.session.application.port.LiveKitTokenPort;
import com.a105.zani.session.application.port.MediaTokenRequest;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/**
 * LiveKit 서버 SDK로 접속 토큰을 발급한다. 벤더 타입은 이 어댑터 안에만 존재한다. roomName은 환경 기준으로 서버가 재구성한다:
 * {@code zani-{environment}-session-{sessionId}}. 학생 기본 토큰에는 Data publish grant를 부여하지 않는다(가이드 §8).
 */
@Component
public class LiveKitTokenAdapter implements LiveKitTokenPort {

    private final LiveKitProperties properties;

    public LiveKitTokenAdapter(LiveKitProperties properties) {
        this.properties = properties;
    }

    @Override
    public IssuedMediaToken issue(MediaTokenRequest request) {
        // 자격증명 미설정 시 빈 시크릿으로 위조 가능한 토큰이 발급되지 않도록 발급 시점에 막는다.
        // (기동 시 검증하면 자격증명 없는 테스트 컨텍스트가 기동 실패하므로 발급 시점에서 검증한다.)
        if (isBlank(properties.url()) || isBlank(properties.apiKey()) || isBlank(properties.apiSecret())) {
            throw new IllegalStateException("LiveKit url/api-key/api-secret is not configured");
        }

        String roomName = "zani-" + properties.environment() + "-session-" + request.sessionId();

        AccessToken token = new AccessToken(properties.apiKey(), properties.apiSecret());
        token.setIdentity(request.identity());
        token.setName(request.displayName());
        // SDK 0.14.0에는 setAttributes가 없어 역할은 metadata(JSON)로 전달한다.
        // FE는 participant.metadata의 role을 읽는다(useRoomParticipants 후속 조정 대상).
        token.setMetadata("{\"role\":\"" + request.role().name() + "\"}");
        token.setTtl(properties.tokenTtl().toMillis());

        List<VideoGrant> grants = new ArrayList<>();
        grants.add(new RoomJoin(true));
        grants.add(new RoomName(roomName));
        grants.add(new CanSubscribe(true));
        grants.add(new CanPublish(true));
        grants.add(new CanPublishData(request.role() == SessionParticipantRole.INSTRUCTOR));
        token.addGrants(grants.toArray(new VideoGrant[0]));

        Instant expiresAt = Instant.now().plus(properties.tokenTtl());
        return new IssuedMediaToken(properties.url(), token.toJwt(), roomName, expiresAt);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
