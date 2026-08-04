package com.a105.zani.recording.application.finalizejob;

/** 세션 병합 작업 실행권. leaseToken이 달라진 뒤 도착한 늦은 결과는 저장할 수 없다. */
public record FinalizationJobLease(Long sessionId, int leaseToken, int attemptCount) {}
