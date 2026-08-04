package com.a105.zani.recording.application.enqueuefinalizations;

import java.time.Instant;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.a105.zani.recording.application.finalizejob.RecordingFinalizationJobPort;
import com.a105.zani.session.application.checkended.CheckSessionEndedUseCase;

/** 세션 소유 도메인에 종료 여부를 묻고, recording은 자기 작업 대기열만 변경한다. */
@Service
@RequiredArgsConstructor
public class EnqueueEndedRecordingSessionsService implements EnqueueEndedRecordingSessionsUseCase {

    private final RecordingFinalizationJobPort jobPort;
    private final CheckSessionEndedUseCase checkSessionEndedUseCase;

    @Override
    public int enqueue(int limit, Instant now) {
        int inserted = 0;
        for (Long sessionId : jobPort.findUnqueuedRecordedSessionIds()) {
            if (inserted >= limit) {
                break;
            }
            if (checkSessionEndedUseCase.isEnded(sessionId) && jobPort.enqueueSession(sessionId, now)) {
                inserted++;
            }
        }
        return inserted;
    }
}
