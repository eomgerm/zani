package com.a105.zani.recording.domain.model;

import org.junit.jupiter.api.Test;

import com.a105.zani.recording.domain.exception.ForbiddenStudentCameraTrackException;
import com.a105.zani.recording.domain.exception.InvalidRecordingTrackException;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingTrackEntryTest {

    private static RecordingTrackEntry entry(
            String identity, SessionParticipantRole role, TrackSource source, String relativePath) {
        return new RecordingTrackEntry(identity, role, source, relativePath, 0, 1_000, null);
    }

    @Test
    void 익명_별칭과_역할이_맞으면_생성된다() {
        assertFalse(entry("instructor", SessionParticipantRole.INSTRUCTOR, TrackSource.CAMERA, "raw/a.webm")
                .isInstructorScreenShare());
        assertTrue(entry("instructor", SessionParticipantRole.INSTRUCTOR, TrackSource.SCREEN_SHARE, "raw/s.webm")
                .isInstructorScreenShare());
        assertTrue(entry("student-001", SessionParticipantRole.STUDENT, TrackSource.MICROPHONE, "raw/m.ogg")
                        .durationMs()
                > 0);
    }

    @Test
    void 별칭_형식이_아닌_identity는_거부된다() {
        // 실명·이메일·userId 등 익명 별칭이 아닌 값은 생성 시점에 차단된다.
        assertThrows(
                InvalidRecordingTrackException.class,
                () -> entry("김태정", SessionParticipantRole.STUDENT, TrackSource.MICROPHONE, "raw/a.ogg"));
        assertThrows(
                InvalidRecordingTrackException.class,
                () -> entry("user@zani.dev", SessionParticipantRole.STUDENT, TrackSource.MICROPHONE, "raw/a.ogg"));
        assertThrows(
                InvalidRecordingTrackException.class,
                () -> entry("42", SessionParticipantRole.STUDENT, TrackSource.MICROPHONE, "raw/a.ogg"));
        assertThrows(
                InvalidRecordingTrackException.class,
                () -> entry("student-1", SessionParticipantRole.STUDENT, TrackSource.MICROPHONE, "raw/a.ogg"));
    }

    @Test
    void 별칭과_역할이_어긋나면_거부된다() {
        assertThrows(
                InvalidRecordingTrackException.class,
                () -> entry("instructor", SessionParticipantRole.STUDENT, TrackSource.MICROPHONE, "raw/a.ogg"));
        assertThrows(
                InvalidRecordingTrackException.class,
                () -> entry("student-001", SessionParticipantRole.INSTRUCTOR, TrackSource.CAMERA, "raw/a.webm"));
    }

    @Test
    void 학생_카메라_항목은_생성_자체가_거부된다() {
        assertThrows(
                ForbiddenStudentCameraTrackException.class,
                () -> entry("student-001", SessionParticipantRole.STUDENT, TrackSource.CAMERA, "raw/c.webm"));
    }

    @Test
    void 세션_루트를_벗어나는_경로는_거부된다() {
        assertThrows(
                InvalidRecordingTrackException.class,
                () -> entry("student-001", SessionParticipantRole.STUDENT, TrackSource.MICROPHONE, "/etc/passwd"));
        assertThrows(
                InvalidRecordingTrackException.class,
                () -> entry("student-001", SessionParticipantRole.STUDENT, TrackSource.MICROPHONE, "raw/../../x.ogg"));
        assertThrows(
                InvalidRecordingTrackException.class,
                () -> entry(
                        "student-001", SessionParticipantRole.STUDENT, TrackSource.MICROPHONE, "C:\\windows\\a.ogg"));
        assertThrows(
                InvalidRecordingTrackException.class,
                () -> entry("student-001", SessionParticipantRole.STUDENT, TrackSource.MICROPHONE, ""));
    }

    @Test
    void 잘못된_시간값은_거부된다() {
        assertThrows(
                InvalidRecordingTrackException.class,
                () -> new RecordingTrackEntry(
                        "student-001",
                        SessionParticipantRole.STUDENT,
                        TrackSource.MICROPHONE,
                        "raw/a.ogg",
                        -1,
                        1,
                        null));
        assertThrows(
                InvalidRecordingTrackException.class,
                () -> new RecordingTrackEntry(
                        "student-001",
                        SessionParticipantRole.STUDENT,
                        TrackSource.MICROPHONE,
                        "raw/a.ogg",
                        0,
                        0,
                        null));
    }

    @Test
    void 역할이나_source가_없으면_거부된다() {
        assertThrows(
                InvalidRecordingTrackException.class,
                () -> entry("student-001", null, TrackSource.MICROPHONE, "raw/a.ogg"));
        assertThrows(
                InvalidRecordingTrackException.class,
                () -> entry("student-001", SessionParticipantRole.STUDENT, null, "raw/a.ogg"));
    }
}
