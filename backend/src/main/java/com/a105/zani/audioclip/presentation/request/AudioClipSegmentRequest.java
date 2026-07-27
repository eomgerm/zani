package com.a105.zani.audioclip.presentation.request;

import java.time.Instant;
import jakarta.validation.constraints.NotNull;

import com.a105.zani.audioclip.application.ingestclip.CapturedSegment;

/** 클립 안의 연속 캡처 구간(ISO-8601 시각). */
public record AudioClipSegmentRequest(
        @NotNull Instant from, @NotNull Instant to) {

    public CapturedSegment toSegment() {
        return new CapturedSegment(from, to);
    }
}
