package com.a105.zani.audioclip.domain.model;

/** 클립을 확보하지 못한 사유. TRANSCRIPTION_FAILED 만 서버가 기록하고 나머지는 강사 클라이언트가 보고한다. */
public enum AudioClipFailureReason {
    /** 버퍼에 쌓인 오디오가 업로드 최소 기준(1분) 미만이다. 수업 시작 직후가 대표적이다. */
    INSUFFICIENT_AUDIO,
    /** 요청 시점에 마이크가 꺼져 있어 확보할 오디오가 없다. */
    MICROPHONE_OFF,
    /** 캡처 자체가 불가능하다(마이크 미게시, MediaRecorder 미지원 등). */
    CAPTURE_UNAVAILABLE,
    /** 재시도까지 모두 실패해 업로드를 포기했다. */
    UPLOAD_FAILED,
    /** 업로드는 받았으나 전사 처리에 실패했다(서버 기록). */
    TRANSCRIPTION_FAILED
}
