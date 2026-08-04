package com.a105.zani.postclass.application.port;

/** 모델이 낸 구간 하나. 검증은 적재하는 쪽의 애그리거트가 한다. */
public record AnalyzedSection(String title, String summary, long startOffsetMs, long endOffsetMs) {}
