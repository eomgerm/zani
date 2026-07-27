package com.a105.zani.audioclip.application.ingestclip;

import com.a105.zani.audioclip.domain.model.AudioClipFailureReason;

/** 클립을 확보하지 못한 사유 보고. availableMs 는 보고 시점의 가용 캡처 시간으로, 재요청 판단 근거가 된다. */
public record ReportAudioClipFailureCommand(
        Long sessionId, Long clipId, Long userId, AudioClipFailureReason reason, Long availableMs) {}
