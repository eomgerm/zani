package com.a105.zani.attention.domain.model.timeline;

/**
 * 학생 본인에게 보여줄 상태 4종(FRD §19.2 하단 상태 막대).
 *
 * <p>참여 상태 6종({@code AttentionState})을 학생 화면 기준으로 줄인 값이다. {@code CONFUSED}·{@code MISSED}·{@code NON_RESPONSE} 는 모두
 * {@link #CHECK_NEEDED} 로 묶는다 — 학생 화면은 셋을 구분하지 않고, 구분해 보여줄수록 자기 응답이 점수로 되돌아온다는 인상만 준다.
 *
 * <p>값이 없는 시각은 이 enum 이 아니라 {@code null} 로 나타낸다. "판단 불가"를 뜻하는 상수를 두면 관측이 아예 없는 시각과 구분되지 않는다.
 */
public enum StudentTimelineState {

    /** 검출기 3·4단계이거나 이해 확인에 "이해했어요"로 답했다. */
    GOOD,

    /** 헷갈려요·놓쳤어요·무응답 중 하나가 유효하다. */
    CHECK_NEEDED,

    /** 카메라가 쓸 만한 영상을 내보내지 않았다. */
    CAMERA_OFF,

    /** 얼굴 판단 불가가 3연속으로 확정됐거나 검출기 자체가 돌지 못했다. */
    UNMEASURABLE
}
