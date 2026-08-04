package com.a105.zani.postclass.application.port;

/** GMS 에 올릴 전사 한 줄. 화자를 담지 않는다 — 세션 식별자를 보낼 수 없고(GMS 가이드 §9), 구간 경계는 내용과 시각에서 나온다. */
public record ContentAnalysisLine(long startOffsetMs, long endOffsetMs, String text) {}
