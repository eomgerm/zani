package com.a105.zani.recording.domain.model;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 녹화 상태 전이의 역행 금지 가드(가이드 §11: 중복·역순 이벤트가 상태를 역행시키지 않는다)를 검증한다. */
class RecordingTest {

    private static final Instant T0 = Instant.parse("2026-07-25T05:00:00Z");
    private static final Instant T1 = Instant.parse("2026-07-25T06:00:00Z");

    private static Recording starting() {
        return Recording.startTrack(1L, 100L, "EG_1", 1, T0);
    }

    @Test
    void 정상_전이는_STARTING에서_RECORDING을_거쳐_COMPLETE로_간다() {
        Recording recording = starting();
        recording.markRecording();
        assertEquals(RecordingStatus.RECORDING, recording.status());

        assertTrue(recording.complete(T1));
        assertEquals(RecordingStatus.COMPLETE, recording.status());
        assertEquals(T1, recording.endedAt());
    }

    @Test
    void 종결_후의_중복_종결은_전이되지_않는다() {
        Recording recording = starting();
        assertTrue(recording.complete(T1));

        assertFalse(recording.complete(T1.plusSeconds(1)));
        assertFalse(recording.fail(T1.plusSeconds(2)));
        assertEquals(RecordingStatus.COMPLETE, recording.status());
        assertEquals(T1, recording.endedAt());
    }

    @Test
    void 종결_후_늦게_도착한_시작_이벤트는_상태를_역행시키지_않는다() {
        Recording recording = starting();
        recording.fail(T1);

        recording.markRecording();

        assertEquals(RecordingStatus.FAILED, recording.status());
    }
}
