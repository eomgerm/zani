package com.a105.zani.postclass.application.port;

/**
 * GMS 에 올릴 전사 한 줄.
 *
 * @param speaker 화자 별칭({@code instructor}, {@code student-001} …). 실명·이메일·세션 참여자 id 가 아니라 {@code RecordingAlias}
 *     값이다(GMS 가이드 §9.2 — 저장은 되돌릴 수 있는 값을, 송출은 되돌릴 수 없는 값을 쓴다). 강사 설명과 학생 질문을 가르는 데 쓴다 — 질문 한 줄마다 구간이 갈리면 타임라인이 쓸모없어진다
 * @param startOffsetMs 수업 시작 기준 발화 시작 시각
 * @param endOffsetMs 수업 시작 기준 발화 종료 시각
 * @param text 발화 내용
 */
public record ContentAnalysisLine(String speaker, long startOffsetMs, long endOffsetMs, String text) {}
