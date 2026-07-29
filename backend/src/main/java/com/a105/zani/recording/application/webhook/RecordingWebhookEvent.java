package com.a105.zani.recording.application.webhook;

import java.time.Instant;
import java.util.List;

import com.a105.zani.recording.domain.model.TrackSource;

/**
 * 서명 검증을 통과한 LiveKit webhook 이벤트의 내부 표현. 벤더 타입은 인프라 어댑터가 이 값으로 변환하며, room 이름 → 세션 ID 해석(환경 검증 포함)도 어댑터가 수행한다. type에 따라
 * 무의미한 필드는 null이다: participant_* 는 participantIdentity를, track_published는 participantIdentity·trackSid·trackSource를,
 * egress_* 는 egressId·egressComplete(종결 이벤트에서만 non-null)· egressTrackSid·egressAudioStream·files를 사용한다.
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
        /**
         * 이 Egress 가 파일이 아니라 WebSocket 으로 오디오를 흘려보내는 코칭용 실행인지. 페이로드에 판단 근거가 없으면 null 이다.
         *
         * <p>코칭 Egress 는 {@code recordings} 행을 만들지 않아 녹화 처리 경로에 태우면 "아직 커밋 전"으로 오해받아 5xx 가 나가고 LiveKit 이 무한 재전송한다. 외부
         * 저장소(Redis 표시)에만 의존하면 그 저장소가 죽었을 때 같은 증상이 돌아오므로, 이벤트 자체에서 읽을 수 있는 이 값을 1차 근거로 쓴다.
         */
        Boolean egressAudioStream,
        List<EgressFileResult> files,
        /**
         * LiveKit이 이벤트를 만든 시각. 출석 시각이 이 값으로 확정되므로 수신 시각을 대신 쓰면 안 된다 — 재전송된 webhook은 원래 발생보다 한참 뒤에 도착할 수 있고, 그러면 최초 입장
         * 시각이 재전송 지연만큼 밀려 접속 1분 판정이 어긋난다. 페이로드에 시각이 없으면 null이고, 그때만 처리 시점 시계로 대체한다.
         */
        Instant occurredAt) {}
