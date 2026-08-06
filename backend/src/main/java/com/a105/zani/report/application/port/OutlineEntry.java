package com.a105.zani.report.application.port;

/**
 * 목차 한 줄 — 제목과 시각만이다.
 *
 * <p>요약을 넣지 않는 것이 요점이다. 제목+시각만이면 40구간이어도 2KB 정도지만 요약까지 넣으면 10KB 로 뛴다. 이 값의 쓸모는 "그건 4번 구간에서 다뤘어요" 같은 라우팅 답변이라 내용이 아니라
 * 위치만 있으면 된다.
 */
public record OutlineEntry(String title, long startOffsetMs, long endOffsetMs) {}
