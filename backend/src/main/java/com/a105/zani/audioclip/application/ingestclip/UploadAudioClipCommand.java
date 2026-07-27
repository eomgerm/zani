package com.a105.zani.audioclip.application.ingestclip;

import java.io.InputStream;

/** 강사 클라이언트가 올린 최근 구간 오디오. audio 스트림은 메모리에서 전사 포트로 그대로 흘려보내고 어디에도 저장하지 않는다. */
public record UploadAudioClipCommand(
        Long sessionId,
        Long clipId,
        Long userId,
        String contentType,
        long sizeBytes,
        InputStream audio,
        UploadedClipMeta meta) {}
