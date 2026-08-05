package com.a105.zani.session.application.getrecordingcontext;

import java.time.Instant;
import java.util.List;

public record GetSessionRecordingContextResult(Instant startedAt, List<SessionRecordingParticipant> participants) {

    public GetSessionRecordingContextResult {
        participants = List.copyOf(participants);
    }
}
