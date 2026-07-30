package com.a105.zani.session.infrastructure.livekit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishData;
import io.livekit.server.CanPublishSources;
import io.livekit.server.CanSubscribe;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import io.livekit.server.VideoGrant;
import org.springframework.stereotype.Component;

import com.a105.zani.session.application.exception.LiveKitNotConfiguredException;
import com.a105.zani.session.application.port.IssuedMediaToken;
import com.a105.zani.session.application.port.LiveKitTokenPort;
import com.a105.zani.session.application.port.MediaTokenRequest;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/**
 * LiveKit 서버 SDK로 접속 토큰을 발급한다. 벤더 타입은 이 어댑터 안에만 존재한다. roomName은 환경 기준으로 서버가 재구성한다:
 * {@code zani-{environment}-session-{sessionId}}. 역할별 publish source는 최소 권한으로 제한하고 Data publish는 모든 역할에서 차단한다(가이드 §8).
 */
@Component
public class LiveKitTokenAdapter implements LiveKitTokenPort {

    /**
     * LiveKit JWT의 {@code canPublishSources}는 TrackSource의 <b>소문자</b> 표기를 문자열로 받는다. 가이드 문서는 protobuf enum 이름(대문자)으로
     * 적어두었지만 서버는 이 문자열과 정확히 비교하므로, 대문자로 넣으면 어떤 source와도 일치하지 않아 publish가 조용히 전부 막힌다.
     */
    private static final List<String> INSTRUCTOR_PUBLISH_SOURCES =
            List.of("camera", "microphone", "screen_share", "screen_share_audio");

    /** 학생 기본 토큰에는 공유 source가 없다. 승인 중에만 UpdateParticipant로 임시 허용한다(가이드 §8). */
    private static final List<String> STUDENT_PUBLISH_SOURCES = List.of("camera", "microphone");

    private final LiveKitProperties properties;

    public LiveKitTokenAdapter(LiveKitProperties properties) {
        this.properties = properties;
    }

    @Override
    public IssuedMediaToken issue(MediaTokenRequest request) {
        // 자격증명 미설정 시 빈 시크릿으로 위조 가능한 토큰이 발급되지 않도록 발급 시점에 막는다.
        // (기동 시 검증하면 자격증명 없는 테스트 컨텍스트가 기동 실패하므로 발급 시점에서 검증한다.)
        if (isBlank(properties.url()) || isBlank(properties.apiKey()) || isBlank(properties.apiSecret())) {
            throw new LiveKitNotConfiguredException();
        }

        String roomName = LiveKitRoomNames.sessionRoom(properties.environment(), request.sessionId());

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
        grants.add(new CanPublishSources(publishSourcesFor(request.role())));
        // DataPacket은 MVP에서 쓰지 않는다. 업무 이벤트는 Spring WebSocket과 Redis로 흐르므로 모든 역할에서 차단한다(가이드 §2·§8).
        grants.add(new CanPublishData(false));
        token.addGrants(grants.toArray(new VideoGrant[0]));

        Instant expiresAt = Instant.now().plus(properties.tokenTtl());
        return new IssuedMediaToken(properties.url(), token.toJwt(), roomName, expiresAt);
    }

    private static List<String> publishSourcesFor(SessionParticipantRole role) {
        return role == SessionParticipantRole.INSTRUCTOR ? INSTRUCTOR_PUBLISH_SOURCES : STUDENT_PUBLISH_SOURCES;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
