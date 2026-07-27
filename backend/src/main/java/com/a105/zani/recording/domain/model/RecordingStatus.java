package com.a105.zani.recording.domain.model;

/** 녹화 실행(recordings 행)의 상태. V1 스키마의 status 컬럼 값과 일치한다. */
public enum RecordingStatus {
    STARTING,
    RECORDING,
    COMPLETE,
    PARTIAL,
    FAILED
}
