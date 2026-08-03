package com.a105.zani.recording.domain.model;

import org.junit.jupiter.api.Test;

import com.a105.zani.recording.domain.exception.InvalidRecordingTrackException;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static com.a105.zani.recording.domain.model.TrackRecordingDecision.FORBIDDEN;
import static com.a105.zani.recording.domain.model.TrackRecordingDecision.RECORD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** LiveKit 백엔드 가이드 §13 Egress 허용 정책 표 전체를 검증한다. */
class RecordingTrackPolicyTest {

    private static TrackRecordingDecision decide(SessionParticipantRole role, TrackSource source) {
        return RecordingTrackPolicy.decide(role, source);
    }

    @Test
    void 강사는_모든_source를_저장한다() {
        for (TrackSource source : TrackSource.values()) {
            assertEquals(RECORD, decide(SessionParticipantRole.INSTRUCTOR, source));
        }
    }

    @Test
    void 학생_마이크는_저장한다() {
        assertEquals(RECORD, decide(SessionParticipantRole.STUDENT, TrackSource.MICROPHONE));
    }

    @Test
    void 학생_화면공유는_영상과_오디오를_모두_저장한다() {
        assertEquals(RECORD, decide(SessionParticipantRole.STUDENT, TrackSource.SCREEN_SHARE));
        assertEquals(RECORD, decide(SessionParticipantRole.STUDENT, TrackSource.SCREEN_SHARE_AUDIO));
    }

    @Test
    void 학생_카메라만_FORBIDDEN() {
        assertEquals(FORBIDDEN, decide(SessionParticipantRole.STUDENT, TrackSource.CAMERA));
    }

    @Test
    void 학생은_카메라를_뺀_모든_source를_저장한다() {
        for (TrackSource source : TrackSource.values()) {
            TrackRecordingDecision expected = source == TrackSource.CAMERA ? FORBIDDEN : RECORD;
            assertEquals(expected, decide(SessionParticipantRole.STUDENT, source));
        }
    }

    @Test
    void 역할이나_source가_없으면_학생_규칙으로_폴백하지_않고_거부한다() {
        assertThrows(InvalidRecordingTrackException.class, () -> decide(null, TrackSource.CAMERA));
        assertThrows(InvalidRecordingTrackException.class, () -> decide(SessionParticipantRole.STUDENT, null));
    }
}
