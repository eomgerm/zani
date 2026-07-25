package com.a105.zani.recording.domain.model;

import org.junit.jupiter.api.Test;

import com.a105.zani.recording.domain.exception.InvalidRecordingTrackException;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static com.a105.zani.recording.domain.model.TrackRecordingDecision.FORBIDDEN;
import static com.a105.zani.recording.domain.model.TrackRecordingDecision.RECORD;
import static com.a105.zani.recording.domain.model.TrackRecordingDecision.SKIP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** LiveKit 백엔드 가이드 §13 Egress 허용 정책 표 전체를 검증한다. */
class RecordingTrackPolicyTest {

    private static TrackRecordingDecision decide(SessionParticipantRole role, TrackSource source, boolean approved) {
        return RecordingTrackPolicy.decide(role, source, approved);
    }

    @Test
    void 강사는_모든_source를_저장한다() {
        for (TrackSource source : TrackSource.values()) {
            assertEquals(RECORD, decide(SessionParticipantRole.INSTRUCTOR, source, false));
        }
    }

    @Test
    void 학생_마이크는_승인과_무관하게_저장한다() {
        assertEquals(RECORD, decide(SessionParticipantRole.STUDENT, TrackSource.MICROPHONE, false));
        assertEquals(RECORD, decide(SessionParticipantRole.STUDENT, TrackSource.MICROPHONE, true));
    }

    @Test
    void 학생_카메라는_승인과_무관하게_FORBIDDEN() {
        assertEquals(FORBIDDEN, decide(SessionParticipantRole.STUDENT, TrackSource.CAMERA, false));
        assertEquals(FORBIDDEN, decide(SessionParticipantRole.STUDENT, TrackSource.CAMERA, true));
    }

    @Test
    void 학생_화면공유는_승인_중에만_저장한다() {
        assertEquals(RECORD, decide(SessionParticipantRole.STUDENT, TrackSource.SCREEN_SHARE, true));
        assertEquals(RECORD, decide(SessionParticipantRole.STUDENT, TrackSource.SCREEN_SHARE_AUDIO, true));
        assertEquals(SKIP, decide(SessionParticipantRole.STUDENT, TrackSource.SCREEN_SHARE, false));
        assertEquals(SKIP, decide(SessionParticipantRole.STUDENT, TrackSource.SCREEN_SHARE_AUDIO, false));
    }

    @Test
    void 역할이나_source가_없으면_학생_규칙으로_폴백하지_않고_거부한다() {
        assertThrows(InvalidRecordingTrackException.class, () -> decide(null, TrackSource.CAMERA, false));
        assertThrows(InvalidRecordingTrackException.class, () -> decide(SessionParticipantRole.STUDENT, null, false));
    }
}
