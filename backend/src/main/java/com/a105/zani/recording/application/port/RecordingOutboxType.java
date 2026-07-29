package com.a105.zani.recording.application.port;

/** recording_outbox 행의 작업 종류. */
public enum RecordingOutboxType {
    /** LiveKit Track Egress 시작 요청(파일 저장). */
    START_TRACK_EGRESS,
    /** 강사 오디오를 WebSocket 으로 실시간 전달하는 Egress 시작 요청(코칭 버퍼용). */
    START_AUDIO_STREAM_EGRESS
}
