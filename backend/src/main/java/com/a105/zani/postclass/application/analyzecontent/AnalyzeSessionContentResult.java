package com.a105.zani.postclass.application.analyzecontent;

/**
 * @param analyzed 이 호출이 실제로 분석해 적재했는지. 이미 리포트가 있었으면 {@code false}(세션당 1회)
 * @param sectionCount 적재된 구간 수
 */
public record AnalyzeSessionContentResult(Long sessionId, boolean analyzed, int sectionCount) {}
