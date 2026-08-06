package com.a105.zani.report.application.port;

/**
 * GMS 로 보낼 발화 한 줄.
 *
 * <p>{@code speaker} 는 <b>이미 별칭이다</b>({@code instructor}, {@code student-001}, {@code unknown}). 참가자 id 를 그대로 두면 이 포트를
 * 구현하는 어댑터가 실명 해석을 다시 해야 하고, 그 순간 익명화 규칙이 두 곳으로 갈린다(GMS 가이드 §9).
 *
 * <p>끝 시각을 싣지 않는다. 모델이 인용으로 짚는 것은 시작 시각이고, 서버도 시작 시각으로 스냅한다 — 안 쓰는 값을 실으면 바이트 예산만 먹는다.
 */
public record AnswerLine(String speaker, long startOffsetMs, String text) {}
