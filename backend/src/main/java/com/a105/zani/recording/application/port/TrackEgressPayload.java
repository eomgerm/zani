package com.a105.zani.recording.application.port;

import com.a105.zani.recording.domain.model.TrackSource;

/**
 * START_TRACK_EGRESS outbox 행의 payload. 익명 alias만 담고 실명·userId는 넣지 않는다.
 *
 * <p>{@code sessionParticipantId} 는 내부 식별자이므로 여기 담아도 된다 — 이 payload 는 DB 안에만 있고 GMS·manifest·파일 경로로 나가지 않는다. 사후 전사가 화자를
 * 알기 위해 이 값이 Egress 시작까지 따라가야 한다(S15P11A105-97).
 *
 * <p>V12 이전에 쌓인 pending 행에는 이 필드가 없어 null 로 역직렬화된다. 그런 payload 는 {@code RecordingOrchestrator} 가 <b>LiveKit 을 호출하기 전에</b> 거부한다 —
 * Egress 를 먼저 띄우고 나서 거부하면 실행은 시작됐는데 {@code recordings} 행이 없어 추적할 수 없게 된다. 거부된 행은 outbox 재시도로 남고, 재시도 상한을 넘기면 그 트랙만
 * 녹화되지 않는다.
 */
public record TrackEgressPayload(
        String trackSid, String recordingAlias, TrackSource source, Long sessionParticipantId) {}
