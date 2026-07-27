package com.a105.zani.audioclip.presentation.request;

import java.time.Instant;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import com.a105.zani.audioclip.application.ingestclip.UploadedClipMeta;

/** 업로드 오디오의 실제 캡처 범위 메타데이터(multipart 의 meta 파트, JSON). */
public record AudioClipMetaRequest(
        @NotNull Instant capturedFrom,
        @NotNull Instant capturedTo,
        @NotNull @Positive Long durationMs,
        @NotEmpty @Valid List<AudioClipSegmentRequest> segments) {

    public UploadedClipMeta toMeta() {
        return new UploadedClipMeta(
                capturedFrom,
                capturedTo,
                durationMs,
                segments.stream().map(AudioClipSegmentRequest::toSegment).toList());
    }
}
