package com.a105.zani.attention.domain.model.timeline;

import com.a105.zani.attention.domain.model.PromptAnswer;

/**
 * 이해 확인 프롬프트에 남은 응답 한 건.
 *
 * @param participantId 응답한 세션 참가자
 * @param offsetMs 기준 시각(ms). 응답 시각이 있으면 그것, 없으면 표시 시각이다(확정 문서 §7.3·§7.5)
 * @param answer 학생이 고른 답
 */
public record PromptRecord(long participantId, long offsetMs, PromptAnswer answer) {}
