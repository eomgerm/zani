package com.a105.zani.attention.domain.model;

/**
 * 학생 참여 상태 6종. 확정 문서 `.agents/attention-coaching-context.md` §1을 따른다.
 *
 * <p>브라우저가 10초 창을 분석해 판정하고, 서버는 판정 결과만 받는다. 원본 영상·랜드마크는 서버로 오지 않는다.
 */
public enum AttentionState {

    /** AI 3·4단계, 또는 이해 확인 프롬프트에 "이해했어요" 응답. */
    GOOD(false),

    /** AI 1·2단계 3연속 후 "헷갈려요" 응답. */
    CONFUSED(true),

    /** AI 1·2단계 3연속 후 "놓쳤어요" 응답. */
    MISSED(true),

    /** AI 1·2단계 3연속 후 30초 무응답. */
    NON_RESPONSE(true),

    /** 얼굴 판단 불가 3연속. */
    UNMEASURABLE(true),

    /** 입장 후 카메라를 끔. */
    CAMERA_OFF(false);

    private final boolean significant;

    AttentionState(boolean significant) {
        this.significant = significant;
    }

    /**
     * 강사 코칭 트리거의 분자에 들어가는 상태인지(확정 문서 §4).
     *
     * <p>CAMERA_OFF는 분자가 아니라 분모 제외 대상이라 여기서는 false다.
     */
    public boolean isSignificant() {
        return significant;
    }
}
