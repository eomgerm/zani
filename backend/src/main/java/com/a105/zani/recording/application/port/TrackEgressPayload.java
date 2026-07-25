package com.a105.zani.recording.application.port;

import com.a105.zani.recording.domain.model.TrackSource;

/** START_TRACK_EGRESS outbox 행의 payload. 익명 alias만 담고 실명·userId는 넣지 않는다. */
public record TrackEgressPayload(String trackSid, String recordingAlias, TrackSource source) {}
