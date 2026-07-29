package com.a105.zani.audioclip.application.captureclip;

/** 코칭 트리거 시점에 세션의 최근 구간 오디오를 전사한다. */
public record CaptureAudioClipCommand(Long sessionId) {}
