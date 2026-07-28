package com.a105.zani.recording.application.webhook;

/** Egress가 남긴 파일 결과. 시각은 epoch millis(0이면 미상). filepath는 Egress 노드 기준 절대 경로다. */
public record EgressFileResult(String filepath, long startedAtMs, long endedAtMs, long sizeBytes) {}
