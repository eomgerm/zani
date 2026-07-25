package com.a105.zani.recording.application.port;

/** recording_outbox 행의 작업 종류. */
public enum RecordingOutboxType {
    /** 방(세션) 생성과 같은 트랜잭션으로 기록되는 녹화 등록 마커. */
    SESSION_RECORDING_ENROLLED,
    /** LiveKit Track Egress 시작 요청. */
    START_TRACK_EGRESS
}
