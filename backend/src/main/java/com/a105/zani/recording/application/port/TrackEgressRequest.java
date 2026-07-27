package com.a105.zani.recording.application.port;

import com.a105.zani.recording.domain.model.TrackSource;

/** LiveKit Track Egress 시작 요청. roomName·저장 경로는 어댑터가 서버 설정으로 재구성한다. */
public record TrackEgressRequest(Long sessionId, String trackSid, String recordingAlias, TrackSource source) {}
