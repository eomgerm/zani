package com.a105.zani.audioclip.presentation.response;

import com.a105.zani.audioclip.application.ingestclip.UploadAudioClipResult;

/** 클립 업로드 처리 결과. alreadyUploaded 는 재시도 중복이 멱등 처리됐음을 뜻한다. */
public record UploadAudioClipResponse(Long clipId, String status, boolean alreadyUploaded) {

    public static UploadAudioClipResponse from(UploadAudioClipResult result) {
        return new UploadAudioClipResponse(result.clipId(), result.status().name(), result.alreadyUploaded());
    }
}
