package com.a105.zani.recording.domain.model;

/** Track Egress 시작 여부 판정 결과. */
public enum TrackRecordingDecision {
    /** Egress를 시작해 저장한다. */
    RECORD,
    /** 저장하지 않고 조용히 건너뛴다(예: 미승인 학생 화면 공유). */
    SKIP,
    /** Egress 요청 생성 자체가 금지된다(학생 카메라). 발견 시 보안 위반으로 기록한다. */
    FORBIDDEN
}
