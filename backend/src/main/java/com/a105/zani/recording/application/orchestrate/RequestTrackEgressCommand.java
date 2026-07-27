package com.a105.zani.recording.application.orchestrate;

import com.a105.zani.recording.domain.model.TrackSource;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/** track_published 이벤트에서 도출된 Track Egress 요청. recordingAlias는 익명 별칭 값이어야 한다. */
public record RequestTrackEgressCommand(
        Long sessionId,
        String trackSid,
        String recordingAlias,
        SessionParticipantRole role,
        TrackSource source,
        boolean studentScreenShareApproved) {}
