package com.a105.zani.recording.application.finalizemanifest;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.a105.zani.recording.application.gettrackfiles.SessionTrackFile;
import com.a105.zani.recording.application.gettrackfiles.SessionTrackFileQueryPort;
import com.a105.zani.recording.domain.exception.InvalidRecordingManifestException;
import com.a105.zani.recording.domain.exception.InvalidRecordingTrackException;
import com.a105.zani.recording.domain.model.RecordingManifest;
import com.a105.zani.recording.domain.model.TrackSource;
import com.a105.zani.session.application.getrecordingcontext.GetSessionRecordingContextQuery;
import com.a105.zani.session.application.getrecordingcontext.GetSessionRecordingContextResult;
import com.a105.zani.session.application.getrecordingcontext.GetSessionRecordingContextUseCase;
import com.a105.zani.session.application.getrecordingcontext.SessionRecordingParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuildFinalizationManifestServiceTest {

    private static final Long SESSION_ID = 269L;
    private static final Instant STARTED_AT = Instant.parse("2026-08-04T01:00:00Z");

    @Mock
    private SessionTrackFileQueryPort trackFileQueryPort;

    @Mock
    private GetSessionRecordingContextUseCase contextUseCase;

    private BuildFinalizationManifestService service;

    @BeforeEach
    void setUp() {
        service = new BuildFinalizationManifestService(trackFileQueryPort, contextUseCase);
        when(contextUseCase.get(new GetSessionRecordingContextQuery(SESSION_ID)))
                .thenReturn(new GetSessionRecordingContextResult(
                        STARTED_AT,
                        List.of(
                                participant(30L, SessionParticipantRole.STUDENT),
                                participant(10L, SessionParticipantRole.INSTRUCTOR),
                                participant(20L, SessionParticipantRole.STUDENT))));
    }

    @Test
    void 참가자_DB_순서로_익명_alias를_만들고_전체_시간순으로_정렬한다() {
        when(trackFileQueryPort.findBySessionId(SESSION_ID))
                .thenReturn(List.of(
                        file(3L, 30L, TrackSource.MICROPHONE, "raw/student-002/mic.ogg", 10_000L, 40_000L),
                        file(2L, 10L, TrackSource.SCREEN_SHARE, "raw/instructor/screen.webm", 5_000L, 25_000L),
                        file(1L, 10L, TrackSource.CAMERA, "raw/instructor/camera.webm", 0L, 60_000L)));

        RecordingManifest manifest = service.build(SESSION_ID);

        assertEquals("269", manifest.sessionId());
        assertEquals(STARTED_AT, manifest.timelineStartedAt());
        assertEquals(
                List.of(0L, 5_000L, 10_000L),
                manifest.tracks().stream().map(track -> track.offsetMs()).toList());
        assertEquals("student-002", manifest.tracks().get(2).participantIdentity());
        assertEquals(30_000L, manifest.tracks().get(2).durationMs());
    }

    @Test
    void 파일명과_무관하게_참가자와_source는_DB_메타데이터를_사용한다() {
        when(trackFileQueryPort.findBySessionId(SESSION_ID))
                .thenReturn(List.of(file(
                        1L, 20L, TrackSource.MICROPHONE, "raw/path-name-does-not-identify-speaker.ogg", 0L, 1_000L)));

        RecordingManifest manifest = service.build(SESSION_ID);

        assertEquals("student-001", manifest.tracks().get(0).participantIdentity());
        assertEquals(TrackSource.MICROPHONE, manifest.tracks().get(0).source());
    }

    @Test
    void 필수_녹화_메타데이터가_없으면_불완전한_manifest를_만들지_않는다() {
        when(trackFileQueryPort.findBySessionId(SESSION_ID))
                .thenReturn(List.of(file(1L, null, TrackSource.MICROPHONE, "raw/mic.ogg", 0L, 1_000L)));

        assertThrows(InvalidRecordingManifestException.class, () -> service.build(SESSION_ID));
    }

    @Test
    void 학생_ID가_null이면_정렬_NPE가_아니라_manifest_계약_예외로_거절한다() {
        when(contextUseCase.get(new GetSessionRecordingContextQuery(SESSION_ID)))
                .thenReturn(new GetSessionRecordingContextResult(
                        STARTED_AT,
                        List.of(
                                participant(null, SessionParticipantRole.STUDENT),
                                participant(10L, SessionParticipantRole.INSTRUCTOR))));

        assertThrows(InvalidRecordingManifestException.class, () -> service.build(SESSION_ID));
    }

    @Test
    void 종료가_시작보다_빠르면_거절한다() {
        when(trackFileQueryPort.findBySessionId(SESSION_ID))
                .thenReturn(List.of(file(1L, 10L, TrackSource.CAMERA, "raw/camera.webm", 2_000L, 1_000L)));

        assertThrows(InvalidRecordingTrackException.class, () -> service.build(SESSION_ID));
    }

    private static SessionRecordingParticipant participant(Long id, SessionParticipantRole role) {
        return new SessionRecordingParticipant(id, role);
    }

    private static SessionTrackFile file(
            Long id,
            Long participantId,
            TrackSource source,
            String storageKey,
            Long startedOffsetMs,
            Long endedOffsetMs) {
        return new SessionTrackFile(id, participantId, source, storageKey, "TR_" + id, startedOffsetMs, endedOffsetMs);
    }
}
