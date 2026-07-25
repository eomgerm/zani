package com.a105.zani.recording.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 176 finalize-recording 설계의 레이아웃 판정·fallback 표를 검증한다(구간 판정 기준은 강사 영상 SCREEN_SHARE). */
class CompositionLayoutTest {

    @Test
    void 공유화면과_강사카메라가_있으면_공유메인_강사PiP() {
        assertEquals(CompositionLayout.SCREEN_SHARE_MAIN_WITH_INSTRUCTOR_PIP, CompositionLayout.of(true, true));
    }

    @Test
    void 공유화면만_있으면_공유화면만_표시() {
        assertEquals(CompositionLayout.SCREEN_SHARE_ONLY, CompositionLayout.of(true, false));
    }

    @Test
    void 공유화면이_없으면_강사카메라가_주화면() {
        assertEquals(CompositionLayout.INSTRUCTOR_MAIN, CompositionLayout.of(false, true));
    }

    @Test
    void 둘_다_없으면_검정화면() {
        assertEquals(CompositionLayout.BLANK, CompositionLayout.of(false, false));
    }
}
