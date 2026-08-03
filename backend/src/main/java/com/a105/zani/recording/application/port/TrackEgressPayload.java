package com.a105.zani.recording.application.port;

import com.a105.zani.recording.domain.model.TrackSource;

/**
 * START_TRACK_EGRESS outbox 행의 payload. 익명 alias만 담고 실명·userId는 넣지 않는다.
 *
 * <p>{@code sessionParticipantId} 는 내부 식별자이므로 여기 담아도 된다 — 이 payload 는 DB 안에만 있고 GMS·manifest·파일 경로로 나가지 않는다. 사후 전사가 화자를
 * 알기 위해 이 값이 Egress 시작까지 따라가야 한다(S15P11A105-97). 기존 pending 행은 이 필드가 없어 null 로 역직렬화되며, 그 경우 Egress 시작이 실패하고 재시도 대상이 된다.
 */
public record TrackEgressPayload(
        String trackSid, String recordingAlias, TrackSource source, Long sessionParticipantId) {}
