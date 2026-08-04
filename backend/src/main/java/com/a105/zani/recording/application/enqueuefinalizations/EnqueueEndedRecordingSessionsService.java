package com.a105.zani.recording.application.enqueuefinalizations;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.a105.zani.recording.application.finalizejob.RecordingFinalizationJobPort;
import com.a105.zani.session.application.checkended.CheckSessionEndedUseCase;

/** 세션 소유 도메인에 종료 여부를 묻고, recording은 자기 작업 대기열만 변경한다. */
@Service
@RequiredArgsConstructor
public class EnqueueEndedRecordingSessionsService implements EnqueueEndedRecordingSessionsUseCase {

    /** 지원 동시 규모(30)를 넘겨 조회해 진행 중 세션이 등록 batch를 가리지 않게 한다. */
    private static final int MINIMUM_DISCOVERY_WINDOW = 64;

    private final RecordingFinalizationJobPort jobPort;
    private final CheckSessionEndedUseCase checkSessionEndedUseCase;

    @Override
    public int enqueue(int limit, Instant now) {
        List<Long> candidates = jobPort.findUnqueuedRecordedSessionIds(Math.max(limit, MINIMUM_DISCOVERY_WINDOW));
        Set<Long> endedSessionIds = checkSessionEndedUseCase.endedSessionIds(candidates);
        int inserted = 0;
        for (Long sessionId : candidates) {
            if (inserted >= limit) {
                break;
            }
            if (endedSessionIds.contains(sessionId) && jobPort.enqueueSession(sessionId, now)) {
                inserted++;
            }
        }
        return inserted;
    }
}
