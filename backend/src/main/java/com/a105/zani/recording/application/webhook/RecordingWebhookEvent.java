package com.a105.zani.recording.application.webhook;

import java.util.List;

import com.a105.zani.recording.domain.model.TrackSource;

/**
 * 서명 검증을 통과한 LiveKit webhook 이벤트의 내부 표현. 벤더 타입은 인프라 어댑터가 이 값으로 변환하며, room 이름 → 세션 ID 해석(환경 검증 포함)도 어댑터가 수행한다. type에 따라
 * 무의미한 필드는 null이다: track_published는 participantIdentity·trackSid·trackSource를, egress_* 는 egressId·egressComplete(종결
 * 이벤트에서만 non-null)· egressTrackSid·files를 사용한다.
 */
public record RecordingWebhookEvent(
        String eventId,
        RecordingWebhookEventType type,
        Long sessionId,
        String participantIdentity,
        String trackSid,
        TrackSource trackSource,
        String egressId,
        Boolean egressComplete,
        String egressTrackSid,
        List<EgressFileResult> files) {}
