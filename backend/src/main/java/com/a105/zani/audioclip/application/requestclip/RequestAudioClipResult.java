package com.a105.zani.audioclip.application.requestclip;

import java.time.Instant;

/** 생성된 클립 요청. windowSeconds 는 클라이언트가 잘라 보낼 최근 구간 길이다. */
public record RequestAudioClipResult(Long clipId, Long sessionId, long windowSeconds, Instant expiresAt) {}
