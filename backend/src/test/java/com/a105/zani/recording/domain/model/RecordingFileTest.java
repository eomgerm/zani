package com.a105.zani.recording.domain.model;

import org.junit.jupiter.api.Test;

import com.a105.zani.recording.domain.exception.InvalidRecordingTrackException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 트랙 파일 생성 가드를 검증한다.
 *
 * <p>화자·트랙 종류는 DB 에서 NULL 을 허용한다(기존 행에 채울 값이 없다). 그래서 신규 파일에 값이 빠지는 것을 막는 책임은 여기에 있다 — 빠진 채로 저장되면 사후
 * 전사(S15P11A105-247)가 "누가 말한 것인가" 에 답할 수 없고, 그 사실은 전사 단계에서야 드러난다.
 */
class RecordingFileTest {

    private static final long SESSION_ID = 100L;
    private static final long RECORDING_ID = 10L;
    private static final long PARTICIPANT_ID = 300L;
    private static final String SAFE_KEY = "raw/participants/student-001/student-001-microphone-TR_a.ogg";

    private static RecordingFile trackFile(Long participantId, TrackSource source, String storageKey) {
        return RecordingFile.trackFile(
                1L, SESSION_ID, RECORDING_ID, participantId, source, storageKey, "TR_a", 0L, 1_000L);
    }

    @Test
    void 화자와_트랙_종류를_보존한다() {
        RecordingFile file = trackFile(PARTICIPANT_ID, TrackSource.MICROPHONE, SAFE_KEY);

        assertEquals(PARTICIPANT_ID, file.sessionParticipantId());
        assertEquals(TrackSource.MICROPHONE, file.trackSource());
        assertEquals(RecordingFile.TYPE_TRACK, file.fileType());
    }

    @Test
    void 화면_공유_오디오도_저장한다() {
        // 녹화 대상이므로 저장은 한다. MVP 전사 대상에서 제외하는 것은 전사 쪽 판단이다(S15P11A105-293).
        RecordingFile file = trackFile(PARTICIPANT_ID, TrackSource.SCREEN_SHARE_AUDIO, SAFE_KEY);

        assertEquals(TrackSource.SCREEN_SHARE_AUDIO, file.trackSource());
    }

    @Test
    void 화자가_없으면_거부한다() {
        assertThrows(InvalidRecordingTrackException.class, () -> trackFile(null, TrackSource.MICROPHONE, SAFE_KEY));
    }

    @Test
    void 트랙_종류가_없으면_거부한다() {
        assertThrows(InvalidRecordingTrackException.class, () -> trackFile(PARTICIPANT_ID, null, SAFE_KEY));
    }

    @Test
    void 상대경로가_아니면_거부한다() {
        // 기존 가드가 유지되는지 함께 확인한다(가이드 §14·§18).
        assertThrows(
                InvalidRecordingTrackException.class,
                () -> trackFile(PARTICIPANT_ID, TrackSource.MICROPHONE, "/srv/zani/recordings/100/raw/x.ogg"));
        assertThrows(
                InvalidRecordingTrackException.class,
                () -> trackFile(PARTICIPANT_ID, TrackSource.MICROPHONE, "raw/../../etc/passwd"));
    }
}
