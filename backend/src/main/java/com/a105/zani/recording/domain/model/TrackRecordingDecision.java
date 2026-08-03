package com.a105.zani.recording.domain.model;

/** Track Egress 시작 여부 판정 결과. 가이드 §13 표의 결과가 둘뿐이라 값도 둘이다. */
public enum TrackRecordingDecision {
    /** Egress를 시작해 저장한다. */
    RECORD,
    /** Egress 요청 생성 자체가 금지된다(학생 카메라). 발견 시 보안 위반으로 기록한다. */
    FORBIDDEN
}
