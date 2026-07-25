package com.a105.zani.recording.domain.model;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.a105.zani.recording.domain.exception.InvalidRecordingManifestException;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecordingManifestTest {

    private static final Instant STARTED_AT = Instant.parse("2026-07-24T05:00:00Z");

    private static RecordingTrackEntry instructorScreenShare(long offsetMs, long durationMs) {
        return new RecordingTrackEntry(
                "instructor",
                SessionParticipantRole.INSTRUCTOR,
                TrackSource.SCREEN_SHARE,
                "raw/instructor-screen-001.webm",
                offsetMs,
                durationMs,
                null);
    }

    private static RecordingTrackEntry instructorCamera() {
        return new RecordingTrackEntry(
                "instructor",
                SessionParticipantRole.INSTRUCTOR,
                TrackSource.CAMERA,
                "raw/instructor-camera-001.webm",
                0,
                60_000,
                null);
    }

    @Test
    void 유효한_manifest는_schema_v1으로_생성된다() {
        RecordingManifest manifest = RecordingManifest.create("session-1", STARTED_AT, List.of(instructorCamera()));

        assertEquals(RecordingManifest.SCHEMA_VERSION, manifest.schemaVersion());
        assertEquals(1, manifest.tracks().size());
    }

    @Test
    void 트랙이_비어_있으면_거부된다() {
        // Worker(finalize_recording.py)가 빈 tracks를 manifest 오류로 거부하므로 생성 시점에 동일하게 막는다.
        assertThrows(
                InvalidRecordingManifestException.class,
                () -> RecordingManifest.create("session-1", STARTED_AT, List.of()));
    }

    @Test
    void 강사_화면공유_구간이_겹치면_거부된다() {
        // [10s, 30s) 와 [25s, 40s) 가 겹침 → 레이아웃 도출이 불가능한 manifest.
        assertThrows(
                InvalidRecordingManifestException.class,
                () -> RecordingManifest.create(
                        "session-1",
                        STARTED_AT,
                        List.of(instructorScreenShare(10_000, 20_000), instructorScreenShare(25_000, 15_000))));
    }

    @Test
    void 강사_화면공유_구간이_이어지면_허용된다() {
        // [10s, 30s) 와 [30s, 45s) 는 반열린 구간이라 겹치지 않는다(sample-manifest와 동일 패턴).
        RecordingManifest manifest = RecordingManifest.create(
                "session-1",
                STARTED_AT,
                List.of(instructorScreenShare(10_000, 20_000), instructorScreenShare(30_000, 15_000)));

        assertEquals(2, manifest.tracks().size());
    }

    @Test
    void 필수값이_빠진_manifest는_거부된다() {
        List<RecordingTrackEntry> tracks = List.of(instructorCamera());
        assertThrows(InvalidRecordingManifestException.class, () -> RecordingManifest.create(" ", STARTED_AT, tracks));
        assertThrows(InvalidRecordingManifestException.class, () -> RecordingManifest.create("s", null, tracks));
        assertThrows(InvalidRecordingManifestException.class, () -> RecordingManifest.create("s", STARTED_AT, null));
    }
}
