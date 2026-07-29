package com.a105.zani.attention.domain.model;

import java.util.Optional;

/**
 * 브라우저 검출기가 10초마다 내놓는 관측 값 7종(확정 문서 §1).
 *
 * <p>학생 상태 6종({@link AttentionState})과 다른 층위다. 이쪽은 <b>관측</b>이고, 학생 상태는 관측과 프롬프트 응답을 합쳐 서버가 <b>해석</b>한 값이다. 이름이 겹치는
 * {@code UNMEASURABLE} 은 여기서는 10초 판정 한 건, 학생 상태 쪽은 3연속으로 확정된 값이다.
 */
public enum DetectorOutcome {

    /** 1단계. */
    NOT_ENGAGED((byte) 1),

    /** 2단계. */
    BARELY_ENGAGED((byte) 2),

    /** 3단계. */
    ENGAGED((byte) 3),

    /** 4단계. */
    HIGHLY_ENGAGED((byte) 4),

    /** 카메라는 영상을 보내는데 얼굴 특징을 못 뽑았다. */
    UNMEASURABLE(null),

    /** 카메라가 쓸 만한 영상을 내보내지 않는다(끔·권한 거부·점유·고장을 모두 포함, §1.3). */
    CAMERA_OFF(null),

    /** 검출기 자체가 돌지 못한다(MediaPipe·ONNX 로드 실패 등). */
    DETECTOR_UNAVAILABLE(null);

    private final Byte engagementLevel;

    DetectorOutcome(Byte engagementLevel) {
        this.engagementLevel = engagementLevel;
    }

    /** 모델이 낸 4단계 값. 4단계가 아닌 출력은 비어 있다. */
    public Optional<Byte> engagementLevel() {
        return Optional.ofNullable(engagementLevel);
    }

    /** 프롬프트 없이 바로 GOOD 으로 보는 3·4단계인지(§2). */
    public boolean isEngaged() {
        return this == ENGAGED || this == HIGHLY_ENGAGED;
    }

    /** 10초 창을 봐야 값이 정해지는 출력인지. 나머지는 보는 순간 확정된다(§4.2). */
    public boolean needsObservationWindow() {
        return this != CAMERA_OFF && this != DETECTOR_UNAVAILABLE;
    }

    /**
     * 이 출력이 그 자리에서 확정하는 학생 상태. 없으면 상태를 바꾸지 않는다.
     *
     * <p>저참여는 프롬프트 응답이 와야 상태가 정해지므로 비어 있고, {@code UNMEASURABLE} 은 3연속이어야 하므로 여기서 정하지 않는다({@link DetectionRunCounters}
     * 참조). {@code DETECTOR_UNAVAILABLE} 은 학생 상태가 아니라 분모 제외 판단에만 쓴다.
     */
    public Optional<AttentionState> immediateState() {
        if (isEngaged()) {
            return Optional.of(AttentionState.GOOD);
        }
        if (this == CAMERA_OFF) {
            return Optional.of(AttentionState.CAMERA_OFF);
        }
        return Optional.empty();
    }
}
