package com.a105.zani.report.application.port;

/** 드래그 앵커가 속한 구간. 질문한 사람이 화면에서 읽고 있던 문장이라, 모델이 "이거" 가 무엇을 가리키는지 알려면 필요하다. */
public record AnchoredSection(String title, String summary, long startOffsetMs, long endOffsetMs) {}
