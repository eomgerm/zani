package com.a105.zani.recording.application.enqueuefinalizations;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.a105.zani.recording.application.finalizejob.RecordingFinalizationJobPort;
import com.a105.zani.session.application.checkended.CheckSessionEndedUseCase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnqueueEndedRecordingSessionsServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

    @Test
    void 후보_조회는_상한을_두고_종료_상태는_한_번에_배치_판정한다() {
        RecordingFinalizationJobPort jobPort = mock(RecordingFinalizationJobPort.class);
        CheckSessionEndedUseCase endedUseCase = mock(CheckSessionEndedUseCase.class);
        var service = new EnqueueEndedRecordingSessionsService(jobPort, endedUseCase);
        List<Long> candidates = List.of(10L, 20L, 30L);
        when(jobPort.findUnqueuedRecordedSessionIds(64)).thenReturn(candidates);
        when(endedUseCase.endedSessionIds(candidates)).thenReturn(Set.of(20L, 30L));
        when(jobPort.enqueueSession(20L, NOW)).thenReturn(true);

        assertEquals(1, service.enqueue(1, NOW));

        verify(endedUseCase).endedSessionIds(candidates);
        verify(jobPort).enqueueSession(20L, NOW);
        verify(jobPort, never()).enqueueSession(10L, NOW);
        verify(jobPort, never()).enqueueSession(30L, NOW);
    }
}
