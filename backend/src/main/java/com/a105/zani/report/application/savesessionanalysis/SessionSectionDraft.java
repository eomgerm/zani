package com.a105.zani.report.application.savesessionanalysis;

/** 아직 검증되지 않은 구간 입력. 도메인 값 객체({@code SessionSection})와 분리해 둔다 — 호출하는 도메인이 검증 전 값을 넘기고, 검증은 애그리거트가 한다. */
public record SessionSectionDraft(String title, String summary, long startOffsetMs, long endOffsetMs) {}
