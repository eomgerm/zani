package com.a105.zani.session.infrastructure.livekit;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.a105.zani.session.application.port.IssuedMediaToken;
import com.a105.zani.session.application.port.MediaTokenRequest;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 어댑터는 오프라인에서 JWT를 서명한다(LiveKit 서버 연결 불필요). */
class LiveKitTokenAdapterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 어댑터 상수를 참조하지 않고 기대값을 따로 적는다. 상수를 잘못 바꾸면 테스트가 같이 따라가지 않고 실패해야 한다. */
    private static final List<String> EXPECTED_PUBLISH_SOURCES =
            List.of("camera", "microphone", "screen_share", "screen_share_audio");

    private final LiveKitProperties properties = new LiveKitProperties(
            "wss://livekit.example.com",
            "devkey",
            "dev-secret-must-be-long-enough-for-hmac-signing",
            "test",
            Duration.ofMinutes(10));

    private final LiveKitTokenAdapter adapter = new LiveKitTokenAdapter(properties);

    @Test
    void buildsTheRoomNameFromTheEnvironmentAndSessionIdAndIssuesAJwt() {
        IssuedMediaToken issued =
                adapter.issue(new MediaTokenRequest("p-456", "홍길동", SessionParticipantRole.STUDENT, 123L));

        assertEquals("wss://livekit.example.com", issued.liveKitUrl());
        assertEquals("zani-test-session-123", issued.roomName());
        assertNotNull(issued.expiresAt());
        assertNotNull(issued.accessToken());
        assertFalse(issued.accessToken().isBlank());
        // JWT는 header.payload.signature 3부분이다.
        assertEquals(3, issued.accessToken().split("\\.").length);
    }

    @Test
    void refusesToIssueWhenTheCredentialsAreMissing() {
        LiveKitTokenAdapter unconfigured =
                new LiveKitTokenAdapter(new LiveKitProperties("wss://x", "", "", "test", Duration.ofMinutes(10)));

        org.junit.jupiter.api.Assertions.assertThrows(
                com.a105.zani.session.application.exception.LiveKitNotConfiguredException.class,
                () -> unconfigured.issue(new MediaTokenRequest("p-1", "n", SessionParticipantRole.STUDENT, 1L)));
    }

    @Test
    void everyRoleAllowsTheSameScreenShareSourcesAndDeniesDataPublish() {
        // 화면 공유에는 역할 제한을 두지 않는다. 학생도 승인 없이 공유를 시작할 수 있어야 하므로 기본 토큰에 공유 source 가 들어가고,
        // 두 역할의 grant 가 같다. 동시 1명 제약은 토큰이 아니라 서버의 활성 공유 상태로 강제한다(티켓 65).
        // 역할을 순회해 단정하므로 역할이 추가되어도 grant 검증이 비어 있지 않다.
        for (SessionParticipantRole role : SessionParticipantRole.values()) {
            JsonNode video = videoGrantOf(role);

            assertEquals(EXPECTED_PUBLISH_SOURCES, publishSourcesOf(video), "publish sources for " + role);
            // DataPacket 은 MVP 에서 쓰지 않는다. 업무 이벤트는 Spring WebSocket 으로 흐른다.
            assertFalse(video.get("canPublishData").asBoolean(), "canPublishData for " + role);
        }
    }

    @Test
    void keepsTheJoinSubscribeRoomAndMetadataContractForBothRoles() {
        for (SessionParticipantRole role : SessionParticipantRole.values()) {
            JsonNode payload = payloadOf(role);
            JsonNode video = payload.get("video");

            assertTrue(video.get("roomJoin").asBoolean(), "roomJoin for " + role);
            assertTrue(video.get("canSubscribe").asBoolean(), "canSubscribe for " + role);
            assertTrue(video.get("canPublish").asBoolean(), "canPublish for " + role);
            assertEquals("zani-test-session-123", video.get("room").asText(), "room for " + role);
            assertEquals("p-456", payload.get("sub").asText(), "identity for " + role);
            assertEquals(
                    "{\"role\":\"" + role.name() + "\"}",
                    payload.get("metadata").asText());
        }
    }

    /** 서명된 토큰의 payload 를 그대로 읽어 grant 를 검증한다. 검증 대상이 클레임이라 서명 확인은 필요 없다. */
    private JsonNode payloadOf(SessionParticipantRole role) {
        IssuedMediaToken issued = adapter.issue(new MediaTokenRequest("p-456", "홍길동", role, 123L));
        String payload =
                new String(Base64.getUrlDecoder().decode(issued.accessToken().split("\\.")[1]), StandardCharsets.UTF_8);
        try {
            return OBJECT_MAPPER.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new AssertionError("토큰 payload 를 JSON 으로 읽지 못했습니다: " + payload, exception);
        }
    }

    private JsonNode videoGrantOf(SessionParticipantRole role) {
        return payloadOf(role).get("video");
    }

    /** canPublishSources 는 문자열 배열이다. 클레임이 아예 없으면 NPE 대신 "클레임이 없다"로 실패하게 먼저 단정한다. */
    private List<String> publishSourcesOf(JsonNode video) {
        JsonNode sources = video.get("canPublishSources");
        assertNotNull(sources, "canPublishSources 클레임이 없습니다");
        List<String> values = new ArrayList<>();
        sources.forEach(source -> values.add(source.asText()));
        return values;
    }
}
