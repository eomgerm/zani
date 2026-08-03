package com.a105.zani.attention.infrastructure.persistence.query;

/**
 * check_prompts 한 행의 조회 결과.
 *
 * @param offsetMs 기준 시각. 응답 시각이 있으면 그것, 없으면 표시 시각이다(확정 문서 §7.3·§7.5)
 * @param response 응답 문자열. {@link ObservationRow} 와 같은 이유로 enum 이 아니다
 */
public record PromptRow(Long participantId, Long offsetMs, String response) {}
