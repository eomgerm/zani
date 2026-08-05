package com.a105.zani.recording.application.issuemediaurl;

import java.time.Instant;

/** 만료 시각을 함께 주어 클라이언트가 재발급 시점을 스스로 정할 수 있게 한다 — 재생 도중 401 을 맞고 나서야 알게 하지 않는다. */
public record IssueMediaUrlResult(String mediaUrl, Instant expiresAt) {}
