package com.a105.zani.recording.domain.model;

/**
 * 최종 강의 MP4 합성 레이아웃 모드(176 finalize-recording 설계와 동일 규칙). 별도 레이아웃 타임라인은 두지 않고, 강사의 영상 SCREEN_SHARE segment 존재 여부로 구간별
 * 레이아웃을 판정한다(레이아웃 판정에 SCREEN_SHARE_AUDIO와 학생 화면 공유는 쓰지 않는다). 실제 렌더링과 해상도·PiP 수치는 운영
 * Worker(infrastructure/media/finalize_recording.py)가 단일 소유한다.
 */
public enum CompositionLayout {
    /** 공유 화면 메인 + 강사 카메라 우측 하단 PiP. */
    SCREEN_SHARE_MAIN_WITH_INSTRUCTOR_PIP,
    /** 공유 화면만 표시(강사 카메라 없음). */
    SCREEN_SHARE_ONLY,
    /** 공유 화면 없음 → 강사 카메라 주 화면(레터박스). */
    INSTRUCTOR_MAIN,
    /** 공유 화면·강사 카메라 모두 없음 → 검정 화면. */
    BLANK;

    /** 구간 내 강사 화면 공유(영상)와 강사 카메라 존재 여부로 레이아웃을 판정한다. */
    public static CompositionLayout of(boolean instructorScreenShareActive, boolean instructorCameraPresent) {
        if (instructorScreenShareActive) {
            return instructorCameraPresent ? SCREEN_SHARE_MAIN_WITH_INSTRUCTOR_PIP : SCREEN_SHARE_ONLY;
        }
        return instructorCameraPresent ? INSTRUCTOR_MAIN : BLANK;
    }
}
