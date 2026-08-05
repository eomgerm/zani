package com.a105.zani.report.application.savesessionanalysis;

/**
 * @param saved 이 호출이 실제로 적재했는지. 이미 리포트가 있으면 {@code false}(세션당 1회, 멱등)
 * @param sectionCount 적재된 구간 수. 이미 있었으면 0
 */
public record SaveSessionAnalysisResult(Long sessionId, boolean saved, int sectionCount) {}
