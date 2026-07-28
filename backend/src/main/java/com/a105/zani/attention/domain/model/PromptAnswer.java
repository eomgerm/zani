package com.a105.zani.attention.domain.model;

import java.util.Optional;

/** 학생이 프롬프트에 낸 답(확정 문서 §3). 30초가 지나면 브라우저가 패널을 닫고 {@link #NO_RESPONSE}를 보낸다. */
public enum PromptAnswer {

    /** 이해했어요. */
    UNDERSTOOD(AttentionState.GOOD),

    /** 헷갈려요. */
    CONFUSED(AttentionState.CONFUSED),

    /** 놓쳤어요. */
    MISSED(AttentionState.MISSED),

    /** 30초 무응답. 이해 확인 프롬프트에서만 상태로 남는다. */
    NO_RESPONSE(AttentionState.NON_RESPONSE),

    /** 자세 안내를 확인함. 참여 상태를 바꾸지는 않는다. */
    ACKNOWLEDGED(null),

    /** 카메라 확인에 "예"(연결이 어렵다). 집단 비율 분모에서 빠진다. */
    CAMERA_UNAVAILABLE(null),

    /** 카메라 확인에 "아니오". */
    CAMERA_AVAILABLE(null);

    private final AttentionState state;

    PromptAnswer(AttentionState state) {
        this.state = state;
    }

    /**
     * 이 답이 확정하는 참여 상태. 없으면 현재 상태를 그대로 둔다.
     *
     * <p>자세 안내·카메라 확인은 참여 상태를 바꾸지 않는다. 자세 안내는 이미 UNMEASURABLE 판정이 남긴 상태를 덮을 이유가 없고, 카메라 확인은 상태가 아니라 분모 제외를 정한다.
     */
    public Optional<AttentionState> confirmedState() {
        return Optional.ofNullable(state);
    }

    /** 이 답이 집단 비율 분모에서 학생을 빼는지(확정 문서 §1의 CAMERA_OFF "예"). */
    public boolean excludesFromDenominator() {
        return this == CAMERA_UNAVAILABLE;
    }
}
