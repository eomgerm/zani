package com.a105.zani.audioclip.application.ingestclip;

import com.a105.zani.audioclip.domain.model.AudioClipRequestStatus;

/** 업로드 처리 결과. alreadyUploaded 는 재시도 중복 업로드가 멱등 처리됐음을 뜻한다. */
public record UploadAudioClipResult(Long clipId, AudioClipRequestStatus status, boolean alreadyUploaded) {}
