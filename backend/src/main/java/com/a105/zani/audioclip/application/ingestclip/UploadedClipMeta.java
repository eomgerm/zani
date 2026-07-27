package com.a105.zani.audioclip.application.ingestclip;

import java.time.Instant;
import java.util.List;

/**
 * 업로드된 클립의 실제 캡처 범위. 수업이 창(5분)보다 짧았거나 음소거 구간이 있으면 durationMs 가 창보다 작다 — 서버는 이 메타만 보고 "요청한 구간 중 어디가 실제로 존재했는지"를 복원한다.
 */
public record UploadedClipMeta(
        Instant capturedFrom, Instant capturedTo, long durationMs, List<CapturedSegment> segments) {}
