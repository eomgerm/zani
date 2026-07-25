package com.a105.zani.recording.infrastructure.livekit;

import java.util.List;

import io.livekit.server.WebhookReceiver;
import livekit.LivekitEgress;
import livekit.LivekitModels;
import livekit.LivekitWebhook;
import org.springframework.stereotype.Component;

import com.a105.zani.recording.application.exception.InvalidWebhookSignatureException;
import com.a105.zani.recording.application.port.RecordingWebhookVerifierPort;
import com.a105.zani.recording.application.webhook.EgressFileResult;
import com.a105.zani.recording.application.webhook.RecordingWebhookEvent;
import com.a105.zani.recording.application.webhook.RecordingWebhookEventType;
import com.a105.zani.recording.domain.model.TrackSource;
import com.a105.zani.session.infrastructure.livekit.LiveKitProperties;
import com.a105.zani.session.infrastructure.livekit.LiveKitRoomNames;

/**
 * LiveKit webhook 서명 검증 어댑터. 벤더 SDK(WebhookReceiver·protobuf) 타입은 이 클래스 안에만 존재하며, 검증 실패는 401로 매핑되는 애플리케이션 예외로 변환한다.
 * 시각(ns)은 epoch millis로 변환해 넘긴다.
 */
@Component
public class LiveKitWebhookVerifierAdapter implements RecordingWebhookVerifierPort {

    private final LiveKitProperties properties;
    private volatile WebhookReceiver receiver;

    public LiveKitWebhookVerifierAdapter(LiveKitProperties properties) {
        this.properties = properties;
    }

    @Override
    public RecordingWebhookEvent verify(String body, String authorizationHeader) {
        LivekitWebhook.WebhookEvent event;
        try {
            event = webhookReceiver().receive(body, bareToken(authorizationHeader));
        } catch (Exception invalidSignature) {
            throw new InvalidWebhookSignatureException(invalidSignature);
        }
        String roomName = event.hasRoom() ? event.getRoom().getName() : null;
        LivekitEgress.EgressInfo egress = event.hasEgressInfo() ? event.getEgressInfo() : null;
        return new RecordingWebhookEvent(
                event.getId(),
                typeOf(event.getEvent()),
                // room 이름 규칙(환경 포함)의 해석은 세션 인프라 소유 유틸로 수행하고, 애플리케이션에는 세션 ID만 넘긴다.
                LiveKitRoomNames.parseSessionId(properties.environment(), roomName)
                        .orElse(null),
                event.hasParticipant() ? event.getParticipant().getIdentity() : null,
                event.hasTrack() ? event.getTrack().getSid() : null,
                event.hasTrack() ? sourceOf(event.getTrack().getSource()) : null,
                egress != null ? egress.getEgressId() : null,
                egress != null ? terminalStateOf(egress.getStatus()) : null,
                egress != null && egress.hasTrack() ? egress.getTrack().getTrackId() : null,
                egress != null ? filesOf(egress) : List.of());
    }

    /** LiveKit 설정에 따라 헤더가 {@code Bearer <token>} 형태로 올 수 있어 접두어를 허용한다(WebhookReceiver는 순수 토큰을 기대). */
    private static String bareToken(String authorizationHeader) {
        if (authorizationHeader != null && authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorizationHeader.substring(7).trim();
        }
        return authorizationHeader;
    }

    private WebhookReceiver webhookReceiver() {
        WebhookReceiver current = receiver;
        if (current == null) {
            synchronized (this) {
                if (receiver == null) {
                    receiver = new WebhookReceiver(properties.apiKey(), properties.apiSecret());
                }
                current = receiver;
            }
        }
        return current;
    }

    private static RecordingWebhookEventType typeOf(String event) {
        return switch (event) {
            case "track_published" -> RecordingWebhookEventType.TRACK_PUBLISHED;
            case "egress_started" -> RecordingWebhookEventType.EGRESS_STARTED;
            case "egress_updated" -> RecordingWebhookEventType.EGRESS_UPDATED;
            case "egress_ended" -> RecordingWebhookEventType.EGRESS_ENDED;
            default -> RecordingWebhookEventType.IGNORED;
        };
    }

    private static TrackSource sourceOf(LivekitModels.TrackSource source) {
        return switch (source) {
            case CAMERA -> TrackSource.CAMERA;
            case MICROPHONE -> TrackSource.MICROPHONE;
            case SCREEN_SHARE -> TrackSource.SCREEN_SHARE;
            case SCREEN_SHARE_AUDIO -> TrackSource.SCREEN_SHARE_AUDIO;
            default -> null;
        };
    }

    /** 종결 이벤트에서만 non-null: COMPLETE → true, FAILED/ABORTED/LIMIT_REACHED → false, 진행 상태 → null. */
    private static Boolean terminalStateOf(LivekitEgress.EgressStatus status) {
        return switch (status) {
            case EGRESS_COMPLETE -> Boolean.TRUE;
            case EGRESS_FAILED, EGRESS_ABORTED, EGRESS_LIMIT_REACHED -> Boolean.FALSE;
            default -> null;
        };
    }

    private static List<EgressFileResult> filesOf(LivekitEgress.EgressInfo info) {
        return info.getFileResultsList().stream()
                .map(file -> new EgressFileResult(
                        file.getFilename(),
                        file.getStartedAt() / 1_000_000,
                        file.getEndedAt() / 1_000_000,
                        file.getSize()))
                .toList();
    }
}
