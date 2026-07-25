package com.a105.zani.recording.application.orchestrate;

import com.a105.zani.recording.domain.model.TrackRecordingDecision;

/** Track Egress 요청 처리 결과. enqueued는 outbox에 새로 등록됐는지(중복이면 false)다. */
public record RequestTrackEgressResult(TrackRecordingDecision decision, boolean enqueued) {}
